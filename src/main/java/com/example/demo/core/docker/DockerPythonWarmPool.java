package com.example.demo.core.docker;

import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.Outcome;
import com.example.demo.api.dto.OutputBlob;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;
import com.example.demo.core.validation.BadRequestException;
import com.example.demo.core.validation.InternalErrorException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

@Component
public class DockerPythonWarmPool {

    private static final Logger log = LoggerFactory.getLogger(DockerPythonWarmPool.class);
    private static final String PYTHON_IMAGE = "python:3.12-alpine";
    private static final int CONTAINER_START_TIMEOUT_MS = 120_000;
    private static final int COMMAND_TIMEOUT_MS = 10_000;

    private final boolean enabled;
    private final int poolSize;
    private final int memoryLimitMb;
    private final int acquireTimeoutMs;
    private final BlockingQueue<WarmContainer> available = new LinkedBlockingQueue<>();
    private final List<WarmContainer> containers = new CopyOnWriteArrayList<>();
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DockerPythonWarmPool(
            @Value("${executor.python.warm-pool.enabled:true}") boolean enabled,
            @Value("${executor.python.warm-pool.size:3}") int poolSize,
            @Value("${executor.python.warm-pool.memory-limit-mb:256}") int memoryLimitMb,
            @Value("${executor.python.warm-pool.acquire-timeout-ms:30000}") int acquireTimeoutMs
    ) {
        this.enabled = enabled;
        this.poolSize = poolSize;
        this.memoryLimitMb = memoryLimitMb;
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!enabled || poolSize <= 0) {
            log.info("Python warm pool disabled");
            return;
        }

        for (int i = 0; i < poolSize; i++) {
            try {
                WarmContainer container = createContainer();
                containers.add(container);
                available.offer(container);
                log.info("Started warm Python container {}", container.containerId());
            } catch (RuntimeException ex) {
                log.error("Failed to start warm Python container", ex);
            }
        }
    }

    public List<TestExecutionResult> executeOptimizedBatch(
            String code,
            List<TestInput> tests,
            ExecutionLimits limits,
            ExecutionPolicy policy
    ) {
        if (!enabled) {
            throw new BadRequestException("Python OPT_BATCH warm pool is disabled");
        }
        if (containers.isEmpty()) {
            throw new InternalErrorException("Python OPT_BATCH warm pool is not available");
        }
        if (policy != null && Boolean.FALSE.equals(policy.getNetworkDisabled())) {
            throw new BadRequestException("Python OPT_BATCH uses fixed networkDisabled=true sandbox policy");
        }
        if (policy != null && Boolean.FALSE.equals(policy.getReadOnlyFs())) {
            throw new BadRequestException("Python OPT_BATCH uses fixed readOnlyFs=true sandbox policy");
        }

        WarmContainer container = acquireContainer();
        boolean recycle = false;
        try {
            WarmBatchResult result = executeInContainer(container, code, tests, limits == null ? new ExecutionLimits() : limits);
            recycle = result.recycleContainer();
            return result.results();
        } catch (RuntimeException ex) {
            recycle = true;
            throw ex;
        } finally {
            if (recycle) {
                replaceContainer(container);
            } else {
                cleanupContainer(container);
                available.offer(container);
            }
        }
    }

    @PreDestroy
    public void shutdown() {
        for (WarmContainer container : containers) {
            removeContainer(container.containerId());
        }
        available.clear();
        containers.clear();
    }

    private WarmBatchResult executeInContainer(
            WarmContainer container,
            String code,
            List<TestInput> tests,
            ExecutionLimits limits
    ) {
        String runDir = "/tmp/exec-" + UUID.randomUUID().toString().replace("-", "");
        int outputLimitBytes = Math.max(1, limits.getOutputLimitKb()) * 1024;

        ProcessResult mkdir = runCommand(
                List.of("docker", "exec", container.containerId(), "mkdir", "-p", runDir),
                null,
                COMMAND_TIMEOUT_MS,
                8 * 1024
        );
        if (mkdir.timedOut() || mkdir.exitCode() == null || mkdir.exitCode() != 0) {
            throw new InternalErrorException("Failed to prepare warm Python work directory: " + outputSummary(mkdir));
        }

        ProcessResult write = runCommand(
                List.of("docker", "exec", "-i", container.containerId(), "sh", "-c", "cat > " + runDir + "/main.py"),
                code,
                COMMAND_TIMEOUT_MS,
                8 * 1024
        );
        if (write.timedOut() || write.exitCode() == null || write.exitCode() != 0) {
            throw new InternalErrorException("Failed to write Python code into warm container: " + outputSummary(write));
        }

        List<TestExecutionResult> results = new ArrayList<>(tests.size());
        boolean encounteredError = false;
        boolean recycleContainer = false;
        Outcome errorOutcome = null;

        for (TestInput test : tests) {
            if (!encounteredError) {
                TestExecutionResult result = executeTest(container, runDir, test, limits, outputLimitBytes);
                results.add(result);
                if (result.getOutcome() != Outcome.OK) {
                    encounteredError = true;
                    errorOutcome = result.getOutcome();
                    recycleContainer = result.getOutcome() == Outcome.TIMEOUT
                            || result.getOutcome() == Outcome.MEMORY_LIMIT
                            || result.getOutcome() == Outcome.INTERNAL_ERROR;
                }
            } else {
                results.add(placeholder(test, errorOutcome));
            }
        }

        return new WarmBatchResult(results, recycleContainer);
    }

    private TestExecutionResult executeTest(
            WarmContainer container,
            String runDir,
            TestInput test,
            ExecutionLimits limits,
            int outputLimitBytes
    ) {
        int timeoutMs = test.getTimeoutMs() != null ? test.getTimeoutMs() : limits.getTimeLimitMs();
        ProcessResult process = runCommand(
                List.of("docker", "exec", "-i", "-w", runDir, container.containerId(), "python3", "main.py"),
                test.getInput() == null ? "" : test.getInput(),
                timeoutMs,
                outputLimitBytes
        );

        if (process.timedOut()) {
            return result(test, Outcome.TIMEOUT, null, process.stdout(), process.stderr(), process.durationMs());
        }

        Outcome outcome;
        Integer exitCode = process.exitCode();
        if (exitCode != null && exitCode == 0) {
            outcome = Outcome.OK;
        } else if (exitCode != null && exitCode == 137) {
            outcome = Outcome.MEMORY_LIMIT;
        } else if (exitCode == null) {
            outcome = Outcome.INTERNAL_ERROR;
        } else {
            outcome = Outcome.RUNTIME_ERROR;
        }

        return result(test, outcome, exitCode, process.stdout(), process.stderr(), process.durationMs());
    }

    private WarmContainer acquireContainer() {
        try {
            WarmContainer container = available.poll(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (container == null) {
                throw new InternalErrorException("No warm Python container became available within " + acquireTimeoutMs + " ms");
            }
            return container;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalErrorException("Interrupted while waiting for a warm Python container", e);
        }
    }

    private WarmContainer createContainer() {
        String containerName = "exec-python-warm-" + UUID.randomUUID();
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("create");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--network");
        cmd.add("none");
        cmd.add("--memory");
        cmd.add(memoryLimitMb + "m");
        cmd.add("--cpus");
        cmd.add("1");
        cmd.add("--pids-limit");
        cmd.add("64");
        cmd.add("--read-only");
        cmd.add("--tmpfs");
        cmd.add("/tmp:rw,nosuid,nodev,size=64m");
        cmd.add(PYTHON_IMAGE);
        cmd.add("tail");
        cmd.add("-f");
        cmd.add("/dev/null");

        ProcessResult create = runCommand(cmd, null, CONTAINER_START_TIMEOUT_MS, 32 * 1024);
        if (create.timedOut() || create.exitCode() == null || create.exitCode() != 0) {
            throw new InternalErrorException("Failed to create warm Python container: " + outputSummary(create));
        }

        ProcessResult start = runCommand(
                List.of("docker", "start", containerName),
                null,
                COMMAND_TIMEOUT_MS,
                8 * 1024
        );
        if (start.timedOut() || start.exitCode() == null || start.exitCode() != 0) {
            removeContainer(containerName);
            throw new InternalErrorException("Failed to start warm Python container: " + outputSummary(start));
        }

        ProcessResult verify = runCommand(
                List.of("docker", "exec", containerName, "python3", "--version"),
                null,
                COMMAND_TIMEOUT_MS,
                8 * 1024
        );
        if (verify.timedOut() || verify.exitCode() == null || verify.exitCode() != 0) {
            removeContainer(containerName);
            throw new InternalErrorException("Warm Python container failed health check: " + outputSummary(verify));
        }

        return new WarmContainer(containerName);
    }

    private void cleanupContainer(WarmContainer container) {
        runCommand(
                List.of("docker", "exec", container.containerId(), "sh", "-c", "killall -q python3 2>/dev/null || true; rm -rf /tmp/exec-*"),
                null,
                COMMAND_TIMEOUT_MS,
                8 * 1024
        );
    }

    private void replaceContainer(WarmContainer oldContainer) {
        containers.remove(oldContainer);
        removeContainer(oldContainer.containerId());
        try {
            WarmContainer replacement = createContainer();
            containers.add(replacement);
            available.offer(replacement);
            log.info("Replaced warm Python container {} with {}", oldContainer.containerId(), replacement.containerId());
        } catch (RuntimeException ex) {
            log.error("Failed to replace warm Python container {}", oldContainer.containerId(), ex);
        }
    }

    private void removeContainer(String containerId) {
        runCommand(List.of("docker", "rm", "-f", containerId), null, COMMAND_TIMEOUT_MS, 8 * 1024);
    }

    private ProcessResult runCommand(
            List<String> command,
            String stdin,
            int timeoutMs,
            int outputLimitBytes
    ) {
        long startNs = System.nanoTime();
        try {
            Process process = new ProcessBuilder(command).start();
            try (OutputStream os = process.getOutputStream()) {
                if (stdin != null) {
                    os.write(stdin.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            CompletableFuture<OutputBlob> stdoutF = readLimited(process.getInputStream(), outputLimitBytes);
            CompletableFuture<OutputBlob> stderrF = readLimited(process.getErrorStream(), outputLimitBytes);

            boolean finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ProcessResult(
                        null,
                        true,
                        safeJoin(stdoutF),
                        safeJoin(stderrF),
                        Duration.ofNanos(System.nanoTime() - startNs).toMillis()
                );
            }

            return new ProcessResult(
                    process.exitValue(),
                    false,
                    safeJoin(stdoutF),
                    safeJoin(stderrF),
                    Duration.ofNanos(System.nanoTime() - startNs).toMillis()
            );
        } catch (IOException e) {
            throw new InternalErrorException("Failed to start docker. Is Docker installed and running? Details: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalErrorException("Docker command interrupted", e);
        }
    }

    private CompletableFuture<OutputBlob> readLimited(InputStream is, int limitBytes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return StreamUtil.readStreamLimited(is, limitBytes);
            } catch (IOException e) {
                return new OutputBlob("", false);
            }
        }, ioExecutor);
    }

    private OutputBlob safeJoin(CompletableFuture<OutputBlob> future) {
        try {
            return future.get(200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return new OutputBlob("", false);
        }
    }

    private TestExecutionResult result(
            TestInput test,
            Outcome outcome,
            Integer exitCode,
            OutputBlob stdout,
            OutputBlob stderr,
            Long durationMs
    ) {
        TestExecutionResult result = new TestExecutionResult();
        result.setTestId(test.getId());
        result.setOutcome(outcome);
        result.setExitCode(exitCode);
        result.setStdout(stdout);
        result.setStderr(stderr);
        result.setDurationMs(durationMs);
        result.setMemoryMb(null);
        return result;
    }

    private TestExecutionResult placeholder(TestInput test, Outcome outcome) {
        TestExecutionResult result = new TestExecutionResult();
        result.setTestId(test.getId());
        result.setOutcome(outcome == null ? Outcome.RUNTIME_ERROR : outcome);
        result.setExitCode(null);
        result.setStdout(new OutputBlob("", false));
        result.setStderr(new OutputBlob("", false));
        result.setDurationMs(null);
        result.setMemoryMb(null);
        return result;
    }

    private String outputSummary(ProcessResult result) {
        if (result == null) {
            return "";
        }
        String stdout = result.stdout() == null ? "" : result.stdout().getData();
        String stderr = result.stderr() == null ? "" : result.stderr().getData();
        return ("stdout=" + stdout + " stderr=" + stderr).trim();
    }

    private record WarmContainer(String containerId) {
    }

    private record WarmBatchResult(List<TestExecutionResult> results, boolean recycleContainer) {
    }

    private record ProcessResult(
            Integer exitCode,
            boolean timedOut,
            OutputBlob stdout,
            OutputBlob stderr,
            Long durationMs
    ) {
    }
}
