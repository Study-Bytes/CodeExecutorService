package com.example.demo.api;

import com.example.demo.api.dto.ExecutionCreateRequest;
import com.example.demo.api.dto.ExecutionResponse;
import com.example.demo.api.dto.ExecutionSessionCreateRequest;
import com.example.demo.api.dto.TestInput;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.ExecutionCancelResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import com.example.demo.core.ExecutionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * Создание новой долгоживущей сессии исполнения (режим STEP).
     * Клиент после этого может по одному отправлять тесты через
     * POST /executions/{id}/tests.
     * Сессия остаётся активной до явной отмены.
     * Возвращает 201 и id созданной сессии.
     */
    @PostMapping("/executions")
    public ResponseEntity<ExecutionResponse> createSession(@Valid @RequestBody ExecutionSessionCreateRequest request) {
        ExecutionResponse response = executionService.createSession(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Пакетный запуск тестов в одном контейнере (режим BATCH).
     * Все тесты выполняются последовательно.
     * Если возникает техническая ошибка (runtime/timeout/memory),
     * последующие тесты не запускаются.
     */
    @PostMapping("/executions/batch")
    public ResponseEntity<ExecutionResponse> createBatchExecution(@Valid @RequestBody ExecutionCreateRequest request) {
        ExecutionResponse response = executionService.executeBatch(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/executions/opt-batch")
    public ResponseEntity<ExecutionResponse> createOptimizedBatchExecution(@Valid @RequestBody ExecutionCreateRequest request) {
        ExecutionResponse response = executionService.executeOptimizedBatch(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Запуск одного теста внутри уже созданной сессии (режим STEP).
     * В запросе передаётся id теста, stdin и при необходимости timeout.
     */
    @PostMapping("/executions/{id}/tests")
    public ResponseEntity<TestExecutionResult> runTest(@PathVariable("id") UUID id,
                                                       @Valid @RequestBody TestInput test) {
        TestExecutionResult result = executionService.runTest(id, test);
        return ResponseEntity.status(HttpStatus.OK).body(result);
    }

    /**
     * Отмена активной сессии и освобождение ресурсов (остановка контейнера).
     * После отмены сессию использовать повторно нельзя.
     */
    @PostMapping("/executions/{id}/cancel")
    public ResponseEntity<ExecutionCancelResponse> cancel(@PathVariable("id") UUID id) {
        ExecutionCancelResponse resp = executionService.cancelSession(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
    }

    /**
     * Получение агрегированных результатов сессии.
     * Удобно для опроса статуса (polling).
     * Для BATCH-режима данные не сохраняются, поэтому
     * этот endpoint применим только к STEP-сессиям.
     */
    @GetMapping("/executions/{id}")
    public ResponseEntity<ExecutionResponse> get(@PathVariable("id") UUID id) {
        ExecutionResponse response = executionService.getSession(id);
        return ResponseEntity.ok(response);
    }
}
