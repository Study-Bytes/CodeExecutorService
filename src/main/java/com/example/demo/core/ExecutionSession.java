package com.example.demo.core;

import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionMetadata;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.ExecutionStatus;
import com.example.demo.api.dto.TestExecutionResult;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Internal representation of an execution session used for STEP mode.
 *
 * A session encapsulates the state needed to execute multiple tests inside
 * a single long‑lived container.  It tracks the container ID, working
 * directory, accumulated test results and aggregate metrics.
 */
public class ExecutionSession {
    private final UUID id;
    private final String language;
    private final ExecutionLimits limits;
    private final ExecutionPolicy policy;
    private final ExecutionMetadata metadata;

    /**
     * Docker container identifier for the running session.
     */
    private final String containerId;
    /**
     * Host directory that contains the user code and is bind‑mounted into the
     * container.  Used for cleanup when the session ends.
     */
    private final Path workDir;

    private ExecutionStatus status = ExecutionStatus.RUNNING;
    private final List<TestExecutionResult> results = new ArrayList<>();
    private long totalDurationMs = 0L;
    private Integer peakMemoryMb = null;

    public ExecutionSession(UUID id,
                            String language,
                            ExecutionLimits limits,
                            ExecutionPolicy policy,
                            ExecutionMetadata metadata,
                            String containerId,
                            Path workDir) {
        this.id = id;
        this.language = language;
        this.limits = limits;
        this.policy = policy;
        this.metadata = metadata;
        this.containerId = containerId;
        this.workDir = workDir;
    }

    public UUID getId() {
        return id;
    }

    public String getLanguage() {
        return language;
    }

    public ExecutionLimits getLimits() {
        return limits;
    }

    public ExecutionPolicy getPolicy() {
        return policy;
    }

    public ExecutionMetadata getMetadata() {
        return metadata;
    }

    public String getContainerId() {
        return containerId;
    }

    public Path getWorkDir() {
        return workDir;
    }

    public ExecutionStatus getStatus() {
        return status;
    }

    public void setStatus(ExecutionStatus status) {
        this.status = status;
    }

    public List<TestExecutionResult> getResults() {
        return results;
    }

    public long getTotalDurationMs() {
        return totalDurationMs;
    }

    public void addDuration(long duration) {
        this.totalDurationMs += duration;
    }

    public Integer getPeakMemoryMb() {
        return peakMemoryMb;
    }

    public void updatePeakMemory(Integer memoryMb) {
        if (memoryMb == null) return;
        if (this.peakMemoryMb == null || memoryMb > this.peakMemoryMb) {
            this.peakMemoryMb = memoryMb;
        }
    }
}