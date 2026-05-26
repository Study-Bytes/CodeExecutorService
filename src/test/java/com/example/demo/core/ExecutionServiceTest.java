package com.example.demo.core;

import com.example.demo.api.dto.ExecutionCancelResponse;
import com.example.demo.api.dto.ExecutionCreateRequest;
import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionMetadata;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.ExecutionResponse;
import com.example.demo.api.dto.ExecutionSessionCreateRequest;
import com.example.demo.api.dto.ExecutionStatus;
import com.example.demo.api.dto.Outcome;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;
import com.example.demo.core.docker.DockerPythonExecutor;
import com.example.demo.core.docker.DockerPythonWarmPool;
import com.example.demo.core.docker.SessionResources;
import com.example.demo.core.executor.LanguageExecutor;
import com.example.demo.core.executor.LanguageExecutorRegistry;
import com.example.demo.core.validation.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExecutionServiceTest {

    @Mock
    private LanguageExecutorRegistry executorRegistry;

    @Mock
    private DockerPythonExecutor dockerPythonExecutor;

    @Mock
    private DockerPythonWarmPool dockerPythonWarmPool;

    @Mock
    private LanguageExecutor languageExecutor;

    @Test
    void executeBatchForPythonUsesWarmPoolAndBuildsFinishedResponse() {
        ExecutionService service = service();
        ExecutionCreateRequest request = batchRequest("python");
        TestExecutionResult first = result("t1", Outcome.OK, 10L, 32);
        TestExecutionResult second = result("t2", Outcome.OK, 7L, null);
        when(executorRegistry.normalize("python")).thenReturn("python");
        when(dockerPythonWarmPool.executeOptimizedBatch(anyString(), any(), any(), any()))
                .thenReturn(List.of(first, second));

        ExecutionResponse response = service.executeBatch(request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
        assertThat(response.getLanguage()).isEqualTo("python");
        assertThat(response.getMetadata()).isSameAs(request.getMetadata());
        assertThat(response.getTests()).containsExactly(first, second);
        assertThat(response.getDurationMs()).isEqualTo(17L);
        assertThat(response.getPeakMemoryMb()).isEqualTo(32);
        verify(executorRegistry, never()).resolve(anyString());
    }

    @Test
    void executeBatchForSqlResolvesLanguageExecutorAndAggregatesMetrics() {
        ExecutionService service = service();
        ExecutionCreateRequest request = batchRequest("SQL");
        TestExecutionResult first = result("sql-1", Outcome.OK, 5L, 64);
        TestExecutionResult second = result("sql-2", Outcome.OK, null, 128);
        when(executorRegistry.normalize("SQL")).thenReturn("sql");
        when(executorRegistry.resolve("sql")).thenReturn(languageExecutor);
        when(languageExecutor.executeBatch(anyString(), any(), any(), any())).thenReturn(List.of(first, second));

        ExecutionResponse response = service.executeBatch(request);

        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
        assertThat(response.getLanguage()).isEqualTo("sql");
        assertThat(response.getTests()).containsExactly(first, second);
        assertThat(response.getDurationMs()).isEqualTo(5L);
        assertThat(response.getPeakMemoryMb()).isEqualTo(128);
        verify(languageExecutor).executeBatch(same(request.getCode()), same(request.getTests()), any(), any());
    }

    @Test
    void executeOptimizedBatchRejectsNonPythonLanguage() {
        ExecutionService service = service();
        ExecutionCreateRequest request = batchRequest("sql");
        when(executorRegistry.normalize("sql")).thenReturn("sql");

        assertThatThrownBy(() -> service.executeOptimizedBatch(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("OPT_BATCH supports only language=python");

        verifyNoInteractions(dockerPythonWarmPool);
    }

    @Test
    void executeOptimizedBatchForPythonPassesDefaultLimitsAndPolicy() {
        ExecutionService service = service();
        ExecutionCreateRequest request = batchRequest("python");
        when(executorRegistry.normalize("python")).thenReturn("python");
        when(dockerPythonWarmPool.executeOptimizedBatch(anyString(), any(), any(), any()))
                .thenReturn(List.of(result("t1", Outcome.OK, 1L, 8)));

        service.executeOptimizedBatch(request);

        ArgumentCaptor<ExecutionLimits> limits = ArgumentCaptor.forClass(ExecutionLimits.class);
        ArgumentCaptor<ExecutionPolicy> policy = ArgumentCaptor.forClass(ExecutionPolicy.class);
        verify(dockerPythonWarmPool).executeOptimizedBatch(
                same(request.getCode()),
                same(request.getTests()),
                limits.capture(),
                policy.capture()
        );
        assertThat(limits.getValue().getTimeLimitMs()).isEqualTo(60000);
        assertThat(limits.getValue().getMemoryLimitMb()).isEqualTo(256);
        assertThat(limits.getValue().getOutputLimitKb()).isEqualTo(256);
        assertThat(policy.getValue().getNetworkDisabled()).isTrue();
        assertThat(policy.getValue().getReadOnlyFs()).isTrue();
    }

    @Test
    void createSessionForPythonCreatesRunningSession() {
        ExecutionService service = service();
        ExecutionSessionCreateRequest request = sessionRequest("python");
        SessionResources resources = new SessionResources("container-1", Path.of("work-dir"));
        when(executorRegistry.normalize("python")).thenReturn("python");
        when(dockerPythonExecutor.createSession(anyString(), any(), any())).thenReturn(resources);

        ExecutionResponse response = service.createSession(request);

        assertThat(response.getId()).isNotNull();
        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.RUNNING);
        assertThat(response.getLanguage()).isEqualTo("python");
        assertThat(response.getMetadata()).isSameAs(request.getMetadata());
        assertThat(response.getTests()).isNull();
        assertThat(response.getDurationMs()).isNull();
        assertThat(response.getPeakMemoryMb()).isNull();
    }

    @Test
    void createSessionRejectsNonPythonLanguage() {
        ExecutionService service = service();
        ExecutionSessionCreateRequest request = sessionRequest("sql");
        when(executorRegistry.normalize("sql")).thenReturn("sql");

        assertThatThrownBy(() -> service.createSession(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("STEP mode supports only language=python");

        verifyNoInteractions(dockerPythonExecutor);
    }

    @Test
    void runTestWhenSessionMissingThrowsBadRequest() {
        ExecutionService service = service();
        UUID sessionId = UUID.randomUUID();

        assertThatThrownBy(() -> service.runTest(sessionId, test("t1")))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Session not found: " + sessionId);
    }

    @Test
    void runTestDelegatesToDockerAndUpdatesSessionAggregates() {
        ExecutionService service = service();
        UUID sessionId = createPythonSession(service);
        TestInput test = test("t1");
        TestExecutionResult result = result("t1", Outcome.OK, 11L, 64);
        when(dockerPythonExecutor.executeInSession(any(), same(test), any())).thenReturn(result);

        TestExecutionResult response = service.runTest(sessionId, test);

        assertThat(response).isSameAs(result);
        ExecutionResponse session = service.getSession(sessionId);
        assertThat(session.getTests()).containsExactly(result);
        assertThat(session.getDurationMs()).isEqualTo(11L);
        assertThat(session.getPeakMemoryMb()).isEqualTo(64);
        ArgumentCaptor<SessionResources> resources = ArgumentCaptor.forClass(SessionResources.class);
        verify(dockerPythonExecutor).executeInSession(resources.capture(), same(test), any());
        assertThat(resources.getValue().getContainerId()).isEqualTo("container-1");
        assertThat(resources.getValue().getWorkDir()).isEqualTo(Path.of("work-dir"));
    }

    @Test
    void runTestTwiceAccumulatesDurationAndKeepsPeakMemory() {
        ExecutionService service = service();
        UUID sessionId = createPythonSession(service);
        TestInput firstTest = test("t1");
        TestInput secondTest = test("t2");
        TestExecutionResult first = result("t1", Outcome.OK, 11L, 64);
        TestExecutionResult second = result("t2", Outcome.OK, 13L, 32);
        when(dockerPythonExecutor.executeInSession(any(), same(firstTest), any())).thenReturn(first);
        when(dockerPythonExecutor.executeInSession(any(), same(secondTest), any())).thenReturn(second);

        service.runTest(sessionId, firstTest);
        service.runTest(sessionId, secondTest);

        ExecutionResponse session = service.getSession(sessionId);
        assertThat(session.getTests()).containsExactly(first, second);
        assertThat(session.getDurationMs()).isEqualTo(24L);
        assertThat(session.getPeakMemoryMb()).isEqualTo(64);
    }

    @Test
    void cancelSessionClosesResourcesAndRemovesSession() {
        ExecutionService service = service();
        UUID sessionId = createPythonSession(service);

        ExecutionCancelResponse response = service.cancelSession(sessionId);

        assertThat(response.getId()).isEqualTo(sessionId.toString());
        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.CANCELLED.name());
        assertThat(response.getMessage()).isEqualTo("Session cancelled");
        ArgumentCaptor<SessionResources> resources = ArgumentCaptor.forClass(SessionResources.class);
        verify(dockerPythonExecutor).closeSession(resources.capture());
        assertThat(resources.getValue().getContainerId()).isEqualTo("container-1");
        assertThatThrownBy(() -> service.getSession(sessionId))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Session not found: " + sessionId);
    }

    private ExecutionService service() {
        return new ExecutionService(executorRegistry, dockerPythonExecutor, dockerPythonWarmPool);
    }

    private UUID createPythonSession(ExecutionService service) {
        ExecutionSessionCreateRequest request = sessionRequest("python");
        when(executorRegistry.normalize("python")).thenReturn("python");
        when(dockerPythonExecutor.createSession(anyString(), any(), any()))
                .thenReturn(new SessionResources("container-1", Path.of("work-dir")));
        return service.createSession(request).getId();
    }

    private ExecutionCreateRequest batchRequest(String language) {
        ExecutionCreateRequest request = new ExecutionCreateRequest();
        request.setLanguage(language);
        request.setCode("print(input())");
        request.setTests(List.of(test("t1"), test("t2")));
        request.setMetadata(metadata());
        return request;
    }

    private ExecutionSessionCreateRequest sessionRequest(String language) {
        ExecutionSessionCreateRequest request = new ExecutionSessionCreateRequest();
        request.setLanguage(language);
        request.setCode("print(input())");
        request.setMetadata(metadata());
        return request;
    }

    private ExecutionMetadata metadata() {
        ExecutionMetadata metadata = new ExecutionMetadata();
        metadata.setTaskId("task-1");
        return metadata;
    }

    private TestInput test(String id) {
        TestInput test = new TestInput();
        test.setId(id);
        test.setInput("hello");
        return test;
    }

    private TestExecutionResult result(String id, Outcome outcome, Long durationMs, Integer memoryMb) {
        TestExecutionResult result = new TestExecutionResult();
        result.setTestId(id);
        result.setOutcome(outcome);
        result.setDurationMs(durationMs);
        result.setMemoryMb(memoryMb);
        return result;
    }
}
