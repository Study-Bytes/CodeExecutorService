package com.example.demo.api.dto;

public class TestExecutionResult {

    private String testId;
    private Outcome outcome;
    private Integer exitCode;
    private OutputBlob stdout;
    private OutputBlob stderr;
    private Long durationMs;
    private Integer memoryMb;

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public void setOutcome(Outcome outcome) {
        this.outcome = outcome;
    }

    public Integer getExitCode() {
        return exitCode;
    }

    public void setExitCode(Integer exitCode) {
        this.exitCode = exitCode;
    }

    public OutputBlob getStdout() {
        return stdout;
    }

    public void setStdout(OutputBlob stdout) {
        this.stdout = stdout;
    }

    public OutputBlob getStderr() {
        return stderr;
    }

    public void setStderr(OutputBlob stderr) {
        this.stderr = stderr;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public Integer getMemoryMb() {
        return memoryMb;
    }

    public void setMemoryMb(Integer memoryMb) {
        this.memoryMb = memoryMb;
    }
}
