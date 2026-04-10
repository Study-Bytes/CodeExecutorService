package com.example.demo.api.dto;

import java.util.List;
import java.util.UUID;

public class ExecutionResponse {

    private UUID id;
    private ExecutionStatus status;
    private String language;

    private Long durationMs;
    private Integer peakMemoryMb;

    private List<TestExecutionResult> tests;

    private ExecutionMetadata metadata;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getPeakMemoryMb() {
        return peakMemoryMb;
    }

    public void setPeakMemoryMb(Integer peakMemoryMb) {
        this.peakMemoryMb = peakMemoryMb;
    }

    public List<TestExecutionResult> getTests() {
        return tests;
    }

    public void setTests(List<TestExecutionResult> tests) {
        this.tests = tests;
    }

    public ExecutionMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ExecutionMetadata metadata) {
        this.metadata = metadata;
    }
}
