package com.example.demo.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for creating a long‑lived execution session (STEP mode).
 *
 * A session holds a prepared container with the user code mounted.  Tests are
 * submitted individually to the session via POST /executions/{id}/tests.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecutionSessionCreateRequest {

    /**
     * Programming language.  Currently only "python" is supported.
     */
    @NotBlank
    private String language;

    /**
     * User code to be executed for each test.  This file is written to
     * <code>main.py</code> in the working directory inside the container.
     */
    @NotBlank
    private String code;

    /**
     * Optional execution limits (timeouts, memory limits, output limits).  If not
     * provided defaults from {@link ExecutionLimits} are applied.
     */
    @Valid
    private ExecutionLimits limits;

    /**
     * Optional execution policy (networkDisabled, readOnlyFs, etc.).  If not
     * provided defaults from {@link ExecutionPolicy} are applied.
     */
    @Valid
    private ExecutionPolicy executionPolicy;

    /**
     * Required arbitrary metadata for tracing (taskId, attemptId, etc.).
     */
    @NotNull
    @Valid
    private ExecutionMetadata metadata;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public ExecutionLimits getLimits() {
        return limits;
    }

    public void setLimits(ExecutionLimits limits) {
        this.limits = limits;
    }

    public ExecutionPolicy getExecutionPolicy() {
        return executionPolicy;
    }

    public void setExecutionPolicy(ExecutionPolicy executionPolicy) {
        this.executionPolicy = executionPolicy;
    }

    public ExecutionMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ExecutionMetadata metadata) {
        this.metadata = metadata;
    }
}