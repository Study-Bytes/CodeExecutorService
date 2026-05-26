package com.example.demo.core;

import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionMetadata;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.TestExecutionResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionSessionTest {

    @Test
    void updatePeakMemoryIgnoresNullAndKeepsMaximum() {
        ExecutionSession session = session();

        session.updatePeakMemory(null);
        session.updatePeakMemory(64);
        session.updatePeakMemory(32);
        session.updatePeakMemory(128);

        assertThat(session.getPeakMemoryMb()).isEqualTo(128);
    }

    @Test
    void addDurationAccumulates() {
        ExecutionSession session = session();

        session.addDuration(10);
        session.addDuration(15);

        assertThat(session.getTotalDurationMs()).isEqualTo(25L);
    }

    private ExecutionSession session() {
        ExecutionMetadata metadata = new ExecutionMetadata();
        metadata.setTaskId("task-1");
        return new ExecutionSession(
                UUID.randomUUID(),
                "python",
                new ExecutionLimits(),
                new ExecutionPolicy(),
                metadata,
                "container-1",
                Path.of("work-dir")
        );
    }
}
