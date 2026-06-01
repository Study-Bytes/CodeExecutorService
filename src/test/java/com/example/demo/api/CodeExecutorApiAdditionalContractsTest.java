package com.example.demo.api;

import com.example.demo.api.dto.ExecutionCancelResponse;
import com.example.demo.api.dto.ExecutionCreateRequest;
import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionMetadata;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.ExecutionResponse;
import com.example.demo.api.dto.ExecutionSessionCreateRequest;
import com.example.demo.api.dto.ExecutionStatus;
import com.example.demo.api.dto.Outcome;
import com.example.demo.api.dto.OutputBlob;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CodeExecutorApiAdditionalContractsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @EnumSource(value = ExecutionStatus.class, names = {"QUEUED", "RUNNING", "FINISHED", "CANCELLED"})
    void executionStatusExposesPublicLifecycleStates(ExecutionStatus status) {
        assertThat(ExecutionStatus.valueOf(status.name())).isEqualTo(status);
    }

    @ParameterizedTest
    @EnumSource(value = Outcome.class, names = {"OK", "RUNTIME_ERROR", "TIMEOUT", "MEMORY_LIMIT", "INTERNAL_ERROR"})
    void outcomeExposesExecutorResultStates(Outcome outcome) {
        assertThat(Outcome.valueOf(outcome.name())).isEqualTo(outcome);
    }

    @ParameterizedTest
    @ValueSource(strings = {"open-1", "hidden_case", " case-with-padding "})
    void validTestInputsHaveNoValidationErrors(String testId) {
        TestInput input = testInput(testId, "stdin", 500);

        assertThat(validatePaths(input)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void metadataValidationRejectsBlankTaskId(String taskId) {
        assertViolates(metadata(taskId), "taskId");
    }

    @Test
    void executionLimitsStoreExplicitOverrides() {
        ExecutionLimits limits = limits(1_500, 512, 64);

        assertThat(limits.getTimeLimitMs()).isEqualTo(1_500);
        assertThat(limits.getMemoryLimitMb()).isEqualTo(512);
        assertThat(limits.getOutputLimitKb()).isEqualTo(64);
    }

    @Test
    void executionPolicyStoresExplicitOverrides() {
        ExecutionPolicy policy = policy(false, false);

        assertThat(policy.getNetworkDisabled()).isFalse();
        assertThat(policy.getReadOnlyFs()).isFalse();
    }

    @Test
    void testInputStoresOptionalPayloadAndTimeout() {
        TestInput input = testInput("sample", "1 2 3", 2_000);

        assertThat(input.getId()).isEqualTo("sample");
        assertThat(input.getInput()).isEqualTo("1 2 3");
        assertThat(input.getTimeoutMs()).isEqualTo(2_000);
    }

    @Test
    void executionMetadataStoresTaskId() {
        ExecutionMetadata metadata = metadata("task-99");

        assertThat(metadata.getTaskId()).isEqualTo("task-99");
    }

    @Test
    void executionCancelResponseStoresAllFields() {
        ExecutionCancelResponse response = new ExecutionCancelResponse();

        response.setId("execution-1");
        response.setStatus("CANCELLED");
        response.setMessage("cancelled by user");

        assertThat(response.getId()).isEqualTo("execution-1");
        assertThat(response.getStatus()).isEqualTo("CANCELLED");
        assertThat(response.getMessage()).isEqualTo("cancelled by user");
    }

    @Test
    void testExecutionResultStoresOutcomeOutputAndMetrics() {
        OutputBlob stdout = new OutputBlob("answer", false);
        OutputBlob stderr = new OutputBlob("warning", true);
        TestExecutionResult result = new TestExecutionResult();

        result.setTestId("hidden-1");
        result.setOutcome(Outcome.RUNTIME_ERROR);
        result.setExitCode(1);
        result.setStdout(stdout);
        result.setStderr(stderr);
        result.setDurationMs(321L);
        result.setMemoryMb(128);

        assertThat(result.getTestId()).isEqualTo("hidden-1");
        assertThat(result.getOutcome()).isEqualTo(Outcome.RUNTIME_ERROR);
        assertThat(result.getExitCode()).isEqualTo(1);
        assertThat(result.getStdout()).isSameAs(stdout);
        assertThat(result.getStderr()).isSameAs(stderr);
        assertThat(result.getDurationMs()).isEqualTo(321L);
        assertThat(result.getMemoryMb()).isEqualTo(128);
    }

    @Test
    void validBatchRequestHasNoValidationErrors() {
        ExecutionCreateRequest request = batchRequest("python", "print(input())", List.of(testInput("open", "42", 1000)));
        request.setMetadata(metadata("task-1"));

        assertThat(validatePaths(request)).isEmpty();
    }

    @Test
    void validSessionRequestHasNoValidationErrors() {
        ExecutionSessionCreateRequest request = sessionRequest("python", "print(1)");
        request.setMetadata(metadata("task-1"));

        assertThat(validatePaths(request)).isEmpty();
    }

    @Test
    void batchRequestCanCarryCustomLimitsAndPolicy() {
        ExecutionLimits limits = limits(2_500, 128, 16);
        ExecutionPolicy policy = policy(true, false);
        ExecutionCreateRequest request = batchRequest("python", "print(1)", List.of(testInput("t1", "", 500)));

        request.setLimits(limits);
        request.setExecutionPolicy(policy);
        request.setMetadata(metadata("task-2"));

        assertThat(request.getLimits()).isSameAs(limits);
        assertThat(request.getExecutionPolicy()).isSameAs(policy);
        assertThat(validatePaths(request)).isEmpty();
    }

    @Test
    void sessionRequestCanCarryCustomLimitsAndPolicy() {
        ExecutionLimits limits = limits(3_000, 256, 32);
        ExecutionPolicy policy = policy(false, true);
        ExecutionSessionCreateRequest request = sessionRequest("python", "print(2)");

        request.setLimits(limits);
        request.setExecutionPolicy(policy);
        request.setMetadata(metadata("task-3"));

        assertThat(request.getLimits()).isSameAs(limits);
        assertThat(request.getExecutionPolicy()).isSameAs(policy);
        assertThat(validatePaths(request)).isEmpty();
    }

    @Test
    void executionResponseCanRepresentCancelledRun() {
        UUID id = UUID.randomUUID();
        ExecutionMetadata metadata = metadata("task-cancelled");
        ExecutionResponse response = new ExecutionResponse();

        response.setId(id);
        response.setStatus(ExecutionStatus.CANCELLED);
        response.setLanguage("python");
        response.setDurationMs(0L);
        response.setPeakMemoryMb(0);
        response.setTests(List.of());
        response.setMetadata(metadata);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(response.getLanguage()).isEqualTo("python");
        assertThat(response.getDurationMs()).isZero();
        assertThat(response.getPeakMemoryMb()).isZero();
        assertThat(response.getTests()).isEmpty();
        assertThat(response.getMetadata()).isSameAs(metadata);
    }

    private void assertViolates(Object value, String expectedPathFragment) {
        Set<String> paths = validatePaths(value);

        assertThat(paths).anySatisfy(path -> assertThat(path).contains(expectedPathFragment));
    }

    private Set<String> validatePaths(Object value) {
        return validator.validate(value)
                .stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static ExecutionCreateRequest batchRequest(String language, String code, List<TestInput> tests) {
        ExecutionCreateRequest request = new ExecutionCreateRequest();
        request.setLanguage(language);
        request.setCode(code);
        request.setTests(tests);
        return request;
    }

    private static ExecutionSessionCreateRequest sessionRequest(String language, String code) {
        ExecutionSessionCreateRequest request = new ExecutionSessionCreateRequest();
        request.setLanguage(language);
        request.setCode(code);
        return request;
    }

    private static TestInput testInput(String id, String input, Integer timeoutMs) {
        TestInput testInput = new TestInput();
        testInput.setId(id);
        testInput.setInput(input);
        testInput.setTimeoutMs(timeoutMs);
        return testInput;
    }

    private static ExecutionMetadata metadata(String taskId) {
        ExecutionMetadata metadata = new ExecutionMetadata();
        metadata.setTaskId(taskId);
        return metadata;
    }

    private static ExecutionLimits limits(Integer timeLimitMs, Integer memoryLimitMb, Integer outputLimitKb) {
        ExecutionLimits limits = new ExecutionLimits();
        limits.setTimeLimitMs(timeLimitMs);
        limits.setMemoryLimitMb(memoryLimitMb);
        limits.setOutputLimitKb(outputLimitKb);
        return limits;
    }

    private static ExecutionPolicy policy(Boolean networkDisabled, Boolean readOnlyFs) {
        ExecutionPolicy policy = new ExecutionPolicy();
        policy.setNetworkDisabled(networkDisabled);
        policy.setReadOnlyFs(readOnlyFs);
        return policy;
    }
}
