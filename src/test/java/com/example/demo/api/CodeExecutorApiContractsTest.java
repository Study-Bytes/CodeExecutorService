package com.example.demo.api;

import com.example.demo.api.dto.ErrorResponse;
import com.example.demo.api.dto.ExecutionCreateRequest;
import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionMetadata;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.ExecutionResponse;
import com.example.demo.api.dto.ExecutionSessionCreateRequest;
import com.example.demo.api.dto.ExecutionStatus;
import com.example.demo.api.dto.OutputBlob;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;
import com.example.demo.core.validation.BadRequestException;
import com.example.demo.core.validation.InternalErrorException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CodeExecutorApiContractsTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void executionLimitsUseSafeDefaults() {
        ExecutionLimits limits = new ExecutionLimits();

        assertThat(limits.getTimeLimitMs()).isEqualTo(60_000);
        assertThat(limits.getMemoryLimitMb()).isEqualTo(256);
        assertThat(limits.getOutputLimitKb()).isEqualTo(256);
    }

    @Test
    void executionPolicyUsesSandboxDefaults() {
        ExecutionPolicy policy = new ExecutionPolicy();

        assertThat(policy.getNetworkDisabled()).isTrue();
        assertThat(policy.getReadOnlyFs()).isTrue();
    }

    @Test
    void outputBlobConstructorAndSettersExposeDataAndTruncationFlag() {
        OutputBlob blob = new OutputBlob("short", false);

        blob.setData("truncated");
        blob.setTruncated(true);

        assertThat(blob.getData()).isEqualTo("truncated");
        assertThat(blob.isTruncated()).isTrue();
    }

    @Test
    void errorResponseConstructorAndSettersExposeMessageAndCode() {
        ErrorResponse response = new ErrorResponse("bad request", 400);

        response.setMessage("internal");
        response.setCode(500);

        assertThat(response.getMessage()).isEqualTo("internal");
        assertThat(response.getCode()).isEqualTo(500);
    }

    @Test
    void healthControllerReturnsHealthAndReadinessStrings() {
        HealthController controller = new HealthController();

        assertThat(controller.health().getBody()).isEqualTo("OK");
        assertThat(controller.ready().getBody()).isEqualTo("READY");
    }

    @Test
    void badRequestExceptionMapsToHttp400() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ErrorResponse> response = handler.handleBadRequest(new BadRequestException("unsupported"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("unsupported");
        assertThat(response.getBody().getCode()).isEqualTo(400);
    }

    @Test
    void internalErrorExceptionMapsToHttp500() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ErrorResponse> response = handler.handleInternal(new InternalErrorException("docker failed"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("docker failed");
        assertThat(response.getBody().getCode()).isEqualTo(500);
    }

    @Test
    void unknownExceptionMapsToHttp500WithUnexpectedPrefix() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        ResponseEntity<ErrorResponse> response = handler.handleUnknown(new IllegalStateException("boom"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("Unexpected error: boom");
        assertThat(response.getBody().getCode()).isEqualTo(500);
    }

    @ParameterizedTest
    @MethodSource("invalidBatchRequests")
    void executionCreateRequestValidationRejectsInvalidPayloads(
            ExecutionCreateRequest request,
            String expectedPathFragment
    ) {
        Set<String> paths = validatePaths(request);

        assertThat(paths).anySatisfy(path -> assertThat(path).contains(expectedPathFragment));
    }

    static Stream<Arguments> invalidBatchRequests() {
        return Stream.of(
                Arguments.of(batchRequest("", "print(1)", List.of(test("t1")), metadata("task-1")), "language"),
                Arguments.of(batchRequest("python", "", List.of(test("t1")), metadata("task-1")), "code"),
                Arguments.of(batchRequest("python", "print(1)", null, metadata("task-1")), "tests"),
                Arguments.of(batchRequest("python", "print(1)", List.of(), metadata("task-1")), "tests"),
                Arguments.of(batchRequest("python", "print(1)", List.of(test("t1")), null), "metadata"),
                Arguments.of(batchRequest("python", "print(1)", List.of(test("t1")), metadata("")), "taskId"),
                Arguments.of(batchRequest("python", "print(1)", List.of(test("")), metadata("task-1")), "id")
        );
    }

    @ParameterizedTest
    @MethodSource("invalidSessionRequests")
    void executionSessionCreateRequestValidationRejectsInvalidPayloads(
            ExecutionSessionCreateRequest request,
            String expectedPathFragment
    ) {
        Set<String> paths = validatePaths(request);

        assertThat(paths).anySatisfy(path -> assertThat(path).contains(expectedPathFragment));
    }

    static Stream<Arguments> invalidSessionRequests() {
        return Stream.of(
                Arguments.of(sessionRequest("", "print(1)", metadata("task-1")), "language"),
                Arguments.of(sessionRequest("python", "", metadata("task-1")), "code"),
                Arguments.of(sessionRequest("python", "print(1)", null), "metadata"),
                Arguments.of(sessionRequest("python", "print(1)", metadata("")), "taskId")
        );
    }

    @Test
    void executionResponseStoresAggregateFieldsAndNestedResults() {
        UUID id = UUID.randomUUID();
        ExecutionMetadata metadata = metadata("task-42");
        TestExecutionResult result = new TestExecutionResult();
        result.setTestId("open-1");
        ExecutionResponse response = new ExecutionResponse();

        response.setId(id);
        response.setStatus(ExecutionStatus.FINISHED);
        response.setLanguage("python");
        response.setDurationMs(15L);
        response.setPeakMemoryMb(64);
        response.setTests(List.of(result));
        response.setMetadata(metadata);

        assertThat(response.getId()).isEqualTo(id);
        assertThat(response.getStatus()).isEqualTo(ExecutionStatus.FINISHED);
        assertThat(response.getLanguage()).isEqualTo("python");
        assertThat(response.getDurationMs()).isEqualTo(15L);
        assertThat(response.getPeakMemoryMb()).isEqualTo(64);
        assertThat(response.getTests()).containsExactly(result);
        assertThat(response.getMetadata()).isSameAs(metadata);
    }

    private Set<String> validatePaths(Object value) {
        return validator.validate(value)
                .stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(Collectors.toSet());
    }

    private static ExecutionCreateRequest batchRequest(
            String language,
            String code,
            List<TestInput> tests,
            ExecutionMetadata metadata
    ) {
        ExecutionCreateRequest request = new ExecutionCreateRequest();
        request.setLanguage(language);
        request.setCode(code);
        request.setTests(tests);
        request.setMetadata(metadata);
        return request;
    }

    private static ExecutionSessionCreateRequest sessionRequest(
            String language,
            String code,
            ExecutionMetadata metadata
    ) {
        ExecutionSessionCreateRequest request = new ExecutionSessionCreateRequest();
        request.setLanguage(language);
        request.setCode(code);
        request.setMetadata(metadata);
        return request;
    }

    private static TestInput test(String id) {
        TestInput input = new TestInput();
        input.setId(id);
        input.setInput("1");
        return input;
    }

    private static ExecutionMetadata metadata(String taskId) {
        ExecutionMetadata metadata = new ExecutionMetadata();
        metadata.setTaskId(taskId);
        return metadata;
    }
}
