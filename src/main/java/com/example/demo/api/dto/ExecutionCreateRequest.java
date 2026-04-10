package com.example.demo.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class ExecutionCreateRequest {

    @NotBlank
    private String language;

    @NotBlank
    private String code;

    @NotEmpty
    @Valid
    private List<TestInput> tests;

    @Valid
    private ExecutionLimits limits;

    @Valid
    private ExecutionPolicy executionPolicy;

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

    public List<TestInput> getTests() {
        return tests;
    }

    public void setTests(List<TestInput> tests) {
        this.tests = tests;
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
