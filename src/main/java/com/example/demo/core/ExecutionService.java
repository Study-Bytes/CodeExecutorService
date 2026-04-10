package com.example.demo.core;

import com.example.demo.api.dto.*;
import com.example.demo.api.dto.ExecutionSessionCreateRequest;
import com.example.demo.core.docker.DockerPythonExecutor;
import com.example.demo.core.validation.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExecutionService {

    private final DockerPythonExecutor dockerPythonExecutor;

    /**
     * Реестр долгоживущих сессий исполнения, индексируемых по UUID.
     * Сессия инкапсулирует запущенный контейнер, внутри которого можно
     * выполнять несколько тестов. Если по запрошенному id сессия не найдена,
     * сервис вернёт ошибку.
     */
    private final Map<UUID, ExecutionSession> sessions = new ConcurrentHashMap<>();

    public ExecutionService(DockerPythonExecutor dockerPythonExecutor) {
        this.dockerPythonExecutor = dockerPythonExecutor;
    }

    /**
     * Пакетное выполнение тестов в одном контейнере (режим BATCH).
     * Входной запрос содержит код и список тестов.
     * Тесты выполняются последовательно внутри одного контейнера.
     * Если возникает техническая ошибка (timeout, превышение памяти,
     * runtime-ошибка или внутренняя ошибка), последующие тесты не
     * выполняются, а для них возвращаются placeholder-результаты.
     *
     * Возвращаемый {@link ExecutionResponse} содержит агрегированную
     * длительность выполнения и пиковое потребление памяти по выполненным тестам.
     *
     * @param request запрос пакетного выполнения (код + тесты)
     * @return агрегированный результат выполнения
     */
    public ExecutionResponse executeBatch(ExecutionCreateRequest request) {
        String language = request.getLanguage().trim().toLowerCase();
        if (!"python".equals(language)) {
            throw new BadRequestException("Only language=python is supported for now");
        }

        ExecutionLimits limits = request.getLimits() != null ? request.getLimits() : new ExecutionLimits();
        ExecutionPolicy policy = request.getExecutionPolicy() != null ? request.getExecutionPolicy() : new ExecutionPolicy();

        List<TestExecutionResult> results = dockerPythonExecutor.executeBatchSingleContainer(
                request.getCode(),
                request.getTests(),
                limits,
                policy
        );

        ExecutionResponse response = new ExecutionResponse();
        // Для batch-режима создаём новый id — постоянной сессии не существует
        response.setId(UUID.randomUUID());
        response.setStatus(ExecutionStatus.FINISHED);
        response.setLanguage(language);
        response.setMetadata(request.getMetadata());
        response.setTests(results);

        long totalDuration = results.stream()
                .map(TestExecutionResult::getDurationMs)
                .filter(d -> d != null)
                .mapToLong(Long::longValue)
                .sum();
        response.setDurationMs(totalDuration);

        Integer peak = results.stream()
                .map(TestExecutionResult::getMemoryMb)
                .filter(m -> m != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        response.setPeakMemoryMb(peak);

        return response;
    }

    /**
     * Создание новой долгоживущей сессии исполнения (режим STEP).
     * Сессия поднимает контейнер с пользовательским кодом и возвращает
     * {@link ExecutionResponse} с идентификатором сессии.
     * Тесты можно выполнять позже через {@link #runTest(UUID, TestInput)}.
     *
     * @param request запрос с языком, кодом, лимитами, политикой и метаданными
     * @return ответ с id сессии и статусом
     */
    public ExecutionResponse createSession(ExecutionSessionCreateRequest request) {
        String language = request.getLanguage().trim().toLowerCase();
        if (!"python".equals(language)) {
            throw new BadRequestException("Only language=python is supported for now");
        }

        ExecutionLimits limits = request.getLimits() != null ? request.getLimits() : new ExecutionLimits();
        ExecutionPolicy policy = request.getExecutionPolicy() != null ? request.getExecutionPolicy() : new ExecutionPolicy();
        UUID id = UUID.randomUUID();

        // Создание docker-сессии (контейнера)
        var sessionResources = dockerPythonExecutor.createSession(request.getCode(), limits, policy);

        // Формирование объекта сессии исполнения
        ExecutionSession session = new ExecutionSession(
                id,
                language,
                limits,
                policy,
                request.getMetadata(),
                sessionResources.getContainerId(),
                sessionResources.getWorkDir()
        );

        sessions.put(id, session);

        ExecutionResponse response = new ExecutionResponse();
        response.setId(id);
        response.setStatus(ExecutionStatus.RUNNING);
        response.setLanguage(language);
        response.setMetadata(request.getMetadata());

        // Пока тесты не запускались — агрегированные поля null
        response.setTests(null);
        response.setDurationMs(null);
        response.setPeakMemoryMb(null);

        return response;
    }

    /**
     * Выполнение одного теста внутри существующей сессии.
     * Сессия должна существовать и быть в статусе RUNNING.
     * Тест выполняется через docker exec, результат возвращается клиенту.
     * Агрегированные метрики сессии обновляются.
     *
     * @param sessionId UUID сессии, полученный из {@link #createSession}
     * @param test      входные данные теста (id, stdin, timeout)
     * @return результат выполнения теста
     */
    public TestExecutionResult runTest(UUID sessionId, TestInput test) {
        ExecutionSession session = sessions.get(sessionId);
        if (session == null) {
            throw new BadRequestException("Session not found: " + sessionId);
        }
        if (session.getStatus() != ExecutionStatus.RUNNING) {
            throw new BadRequestException("Session is not running: " + sessionId);
        }

        TestExecutionResult result = dockerPythonExecutor.executeInSession(
                new com.example.demo.core.docker.SessionResources(session.getContainerId(), session.getWorkDir()),
                test,
                session.getLimits()
        );

        // Добавляем результат в список выполненных тестов
        session.getResults().add(result);

        // Обновляем суммарную длительность
        if (result.getDurationMs() != null) {
            session.addDuration(result.getDurationMs());
        }

        // Обновляем пиковое потребление памяти
        session.updatePeakMemory(result.getMemoryMb());

        return result;
    }

    /**
     * Отмена запущенной сессии: остановка и удаление контейнера,
     * освобождение ресурсов. После отмены сессия больше не может
     * использоваться повторно.
     *
     * @param sessionId идентификатор сессии
     * @return ответ об отмене
     */
    public ExecutionCancelResponse cancelSession(UUID sessionId) {
        ExecutionSession session = sessions.remove(sessionId);
        if (session == null) {
            throw new BadRequestException("Session not found: " + sessionId);
        }

        dockerPythonExecutor.closeSession(
                new com.example.demo.core.docker.SessionResources(
                        session.getContainerId(),
                        session.getWorkDir()
                )
        );

        session.setStatus(ExecutionStatus.CANCELLED);

        ExecutionCancelResponse resp = new ExecutionCancelResponse();
        resp.setId(sessionId.toString());
        resp.setStatus(session.getStatus().name());
        resp.setMessage("Session cancelled");

        return resp;
    }

    /**
     * Получение текущего состояния сессии (RUNNING или FINISHED).
     * Возвращает агрегированную длительность, пиковую память
     * и список уже выполненных тестов.
     *
     * @param sessionId идентификатор сессии
     * @return агрегированный результат исполнения
     */
    public ExecutionResponse getSession(UUID sessionId) {
        ExecutionSession session = sessions.get(sessionId);
        if (session == null) {
            throw new BadRequestException("Session not found: " + sessionId);
        }

        ExecutionResponse resp = new ExecutionResponse();
        resp.setId(sessionId);
        resp.setStatus(session.getStatus());
        resp.setLanguage(session.getLanguage());
        resp.setMetadata(session.getMetadata());
        resp.setTests(session.getResults());
        resp.setDurationMs(session.getTotalDurationMs());
        resp.setPeakMemoryMb(session.getPeakMemoryMb());

        return resp;
    }
}
