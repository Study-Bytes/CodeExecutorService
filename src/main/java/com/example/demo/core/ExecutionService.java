package com.example.demo.core;

import com.example.demo.api.dto.ExecutionCancelResponse;
import com.example.demo.api.dto.ExecutionCreateRequest;
import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionMetadata;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.ExecutionResponse;
import com.example.demo.api.dto.ExecutionSessionCreateRequest;
import com.example.demo.api.dto.ExecutionStatus;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;
import com.example.demo.core.docker.DockerPythonExecutor;
import com.example.demo.core.docker.DockerPythonWarmPool;
import com.example.demo.core.docker.SessionResources;
import com.example.demo.core.executor.LanguageExecutor;
import com.example.demo.core.executor.LanguageExecutorRegistry;
import com.example.demo.core.validation.BadRequestException;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ExecutionService {

    private final LanguageExecutorRegistry executorRegistry;
    private final DockerPythonExecutor dockerPythonExecutor;
    private final DockerPythonWarmPool dockerPythonWarmPool;
    private final Map<UUID, ExecutionSession> sessions = new ConcurrentHashMap<>();

    public ExecutionService(
            LanguageExecutorRegistry executorRegistry,
            DockerPythonExecutor dockerPythonExecutor,
            DockerPythonWarmPool dockerPythonWarmPool
    ) {
        this.executorRegistry = executorRegistry;
        this.dockerPythonExecutor = dockerPythonExecutor;
        this.dockerPythonWarmPool = dockerPythonWarmPool;
    }

    public ExecutionResponse executeBatch(ExecutionCreateRequest request) {
        String language = executorRegistry.normalize(request.getLanguage());
        ExecutionLimits limits = request.getLimits() != null ? request.getLimits() : new ExecutionLimits();
        ExecutionPolicy policy = request.getExecutionPolicy() != null ? request.getExecutionPolicy() : new ExecutionPolicy();

        if ("python".equals(language)) {
            List<TestExecutionResult> results = dockerPythonWarmPool.executeOptimizedBatch(
                    request.getCode(),
                    request.getTests(),
                    limits,
                    policy
            );
            return finishedResponse(language, request.getMetadata(), results);
        }

        LanguageExecutor executor = executorRegistry.resolve(language);
        List<TestExecutionResult> results = executor.executeBatch(
                request.getCode(),
                request.getTests(),
                limits,
                policy
        );

        return finishedResponse(language, request.getMetadata(), results);
    }

    public ExecutionResponse executeOptimizedBatch(ExecutionCreateRequest request) {
        String language = executorRegistry.normalize(request.getLanguage());
        if (!"python".equals(language)) {
            throw new BadRequestException("OPT_BATCH supports only language=python");
        }

        ExecutionLimits limits = request.getLimits() != null ? request.getLimits() : new ExecutionLimits();
        ExecutionPolicy policy = request.getExecutionPolicy() != null ? request.getExecutionPolicy() : new ExecutionPolicy();

        List<TestExecutionResult> results = dockerPythonWarmPool.executeOptimizedBatch(
                request.getCode(),
                request.getTests(),
                limits,
                policy
        );

        return finishedResponse(language, request.getMetadata(), results);
    }

    public ExecutionResponse createSession(ExecutionSessionCreateRequest request) {
        String language = executorRegistry.normalize(request.getLanguage());
        if (!"python".equals(language)) {
            throw new BadRequestException("STEP mode supports only language=python");
        }

        ExecutionLimits limits = request.getLimits() != null ? request.getLimits() : new ExecutionLimits();
        ExecutionPolicy policy = request.getExecutionPolicy() != null ? request.getExecutionPolicy() : new ExecutionPolicy();
        UUID id = UUID.randomUUID();

        SessionResources sessionResources = dockerPythonExecutor.createSession(request.getCode(), limits, policy);
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
        response.setTests(null);
        response.setDurationMs(null);
        response.setPeakMemoryMb(null);
        return response;
    }

    public TestExecutionResult runTest(UUID sessionId, TestInput test) {
        ExecutionSession session = sessions.get(sessionId);
        if (session == null) {
            throw new BadRequestException("Session not found: " + sessionId);
        }
        if (session.getStatus() != ExecutionStatus.RUNNING) {
            throw new BadRequestException("Session is not running: " + sessionId);
        }

        TestExecutionResult result = dockerPythonExecutor.executeInSession(
                new SessionResources(session.getContainerId(), session.getWorkDir()),
                test,
                session.getLimits()
        );

        session.getResults().add(result);
        if (result.getDurationMs() != null) {
            session.addDuration(result.getDurationMs());
        }
        session.updatePeakMemory(result.getMemoryMb());

        return result;
    }

    public ExecutionCancelResponse cancelSession(UUID sessionId) {
        ExecutionSession session = sessions.remove(sessionId);
        if (session == null) {
            throw new BadRequestException("Session not found: " + sessionId);
        }

        dockerPythonExecutor.closeSession(new SessionResources(session.getContainerId(), session.getWorkDir()));
        session.setStatus(ExecutionStatus.CANCELLED);

        ExecutionCancelResponse resp = new ExecutionCancelResponse();
        resp.setId(sessionId.toString());
        resp.setStatus(session.getStatus().name());
        resp.setMessage("Session cancelled");
        return resp;
    }

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

    private ExecutionResponse finishedResponse(
            String language,
            ExecutionMetadata metadata,
            List<TestExecutionResult> results
    ) {
        ExecutionResponse response = new ExecutionResponse();
        response.setId(UUID.randomUUID());
        response.setStatus(ExecutionStatus.FINISHED);
        response.setLanguage(language);
        response.setMetadata(metadata);
        response.setTests(results);

        long totalDuration = results.stream()
                .map(TestExecutionResult::getDurationMs)
                .filter(duration -> duration != null)
                .mapToLong(Long::longValue)
                .sum();
        response.setDurationMs(totalDuration);

        Integer peak = results.stream()
                .map(TestExecutionResult::getMemoryMb)
                .filter(memory -> memory != null)
                .max(Comparator.naturalOrder())
                .orElse(null);
        response.setPeakMemoryMb(peak);

        return response;
    }
}
