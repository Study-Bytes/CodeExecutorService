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
public class DockerSqlWarmPool {

    private static final Logger log = LoggerFactory.getLogger(DockerSqlWarmPool.class);
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";
    private static final int CONTAINER_START_TIMEOUT_MS = 120_000;
    private static final int COMMAND_TIMEOUT_MS = 10_000;

    private final boolean enabled;
    private final int poolSize;
    private final int memoryLimitMb;
    private final int acquireTimeoutMs;
    private final BlockingQueue<WarmContainer> available = new LinkedBlockingQueue<>();
    private final List<WarmContainer> containers = new CopyOnWriteArrayList<>();
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public DockerSqlWarmPool(
            @Value("${executor.sql.warm-pool.enabled:true}") boolean enabled,
            @Value("${executor.sql.warm-pool.size:3}") int poolSize,
            @Value("${executor.sql.warm-pool.memory-limit-mb:256}") int memoryLimitMb,
            @Value("${executor.sql.warm-pool.acquire-timeout-ms:30000}") int acquireTimeoutMs
    ) {
        this.enabled = enabled;
        this.poolSize = poolSize;
        this.memoryLimitMb = memoryLimitMb;
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        if (!enabled || poolSize <= 0) {
            log.info("SQL warm pool disabled");
            return;
        }

        for (int i = 0; i < poolSize; i++) {
            try {
                WarmContainer container = createContainer();
                containers.add(container);
                available.offer(container);
                log.info("Started warm SQL container {}", container.containerId());
            } catch (RuntimeException ex) {
                log.error("Failed to start warm SQL container", ex);
            }
        }
    }

    public List<TestExecutionResult> executeBatch(
            String code,
            List<TestInput> tests,
            ExecutionLimits limits,
            ExecutionPolicy policy
    ) {
        if (!enabled) {
            throw new BadRequestException("SQL warm pool is disabled");
        }
        if (containers.isEmpty()) {
            throw new InternalErrorException("SQL warm pool is not available");
        }
        if (policy != null && Boolean.FALSE.equals(policy.getNetworkDisabled())) {
            throw new BadRequestException("SQL warm pool uses fixed networkDisabled=true sandbox policy");
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
        List<TestExecutionResult> results = new ArrayList<>(tests.size());
        boolean encounteredError = false;
        boolean recycleContainer = false;
        Outcome errorOutcome = null;

        for (TestInput test : tests) {
            if (!encounteredError) {
                TestExecutionResult result = executeSingleTest(container, code, test, limits);
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

    private TestExecutionResult executeSingleTest(
            WarmContainer container,
            String code,
            TestInput test,
            ExecutionLimits limits
    ) {
        String dbName = "test_" + UUID.randomUUID().toString().replace("-", "");
        createDatabase(container.containerId(), dbName);

        try {
            int timeoutMs = test.getTimeoutMs() != null ? test.getTimeoutMs() : limits.getTimeLimitMs();
            int outputLimitBytes = Math.max(1, limits.getOutputLimitKb()) * 1024;

            if (hasText(test.getInput())) {
                ProcessResult setup = runPsql(container.containerId(), dbName, test.getInput(), timeoutMs, outputLimitBytes);
                if (setup.timedOut()) {
                    return result(test, Outcome.TIMEOUT, null, setup.stdout(), setup.stderr(), setup.durationMs());
                }
                if (setup.exitCode() == null || setup.exitCode() != 0) {
                    return result(test, Outcome.RUNTIME_ERROR, setup.exitCode(), setup.stdout(), setup.stderr(), setup.durationMs());
                }
            }

            ProcessResult query = runPsql(container.containerId(), dbName, code, timeoutMs, outputLimitBytes);
            if (query.timedOut()) {
                return result(test, Outcome.TIMEOUT, null, query.stdout(), query.stderr(), query.durationMs());
            }
            Outcome outcome = query.exitCode() != null && query.exitCode() == 0
                    ? Outcome.OK
                    : Outcome.RUNTIME_ERROR;
            return result(test, outcome, query.exitCode(), query.stdout(), query.stderr(), query.durationMs());
        } finally {
            dropDatabase(container.containerId(), dbName);
        }
    }

    private WarmContainer acquireContainer() {
        try {
            WarmContainer container = available.poll(acquireTimeoutMs, TimeUnit.MILLISECONDS);
            if (container == null) {
                throw new InternalErrorException("No warm SQL container became available within " + acquireTimeoutMs + " ms");
            }
            return container;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalErrorException("Interrupted while waiting for a warm SQL container", e);
        }
    }

    private WarmContainer createContainer() {
        String containerName = "exec-sql-warm-" + UUID.randomUUID();
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("-d");
        cmd.add("--name");
        cmd.add(containerName);
        cmd.add("--network");
        cmd.add("none");
        cmd.add("--memory");
        cmd.add(memoryLimitMb + "m");
        cmd.add("--cpus");
        cmd.add("1");
        cmd.add("--pids-limit");
        cmd.add("128");
        cmd.add("-e");
        cmd.add("POSTGRES_HOST_AUTH_METHOD=trust");
        cmd.add("-e");
        cmd.add("POSTGRES_DB=runner");
        cmd.add("-e");
        cmd.add("POSTGRES_INITDB_ARGS=--nosync");
        cmd.add(POSTGRES_IMAGE);

        ProcessResult create = runCommand(cmd, null, CONTAINER_START_TIMEOUT_MS, 32 * 1024);
        if (create.timedOut() || create.exitCode() == null || create.exitCode() != 0) {
            throw new InternalErrorException("Failed to start warm SQL container: " + outputSummary(create));
        }

        waitUntilReady(containerName);
        return new WarmContainer(containerName);
    }

    private void waitUntilReady(String containerName) {
        ProcessResult lastResult = null;
        for (int attempt = 0; attempt < 40; attempt++) {
            lastResult = runCommand(
                    List.of("docker", "exec", containerName, "pg_isready", "-U", "postgres", "-d", "runner"),
                    null,
                    2_000,
                    8 * 1024
            );
            if (!lastResult.timedOut() && lastResult.exitCode() != null && lastResult.exitCode() == 0) {
                return;
            }

            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InternalErrorException("Interrupted while waiting for PostgreSQL readiness", e);
            }
        }

        removeContainer(containerName);
        throw new InternalErrorException("PostgreSQL did not become ready: " + outputSummary(lastResult));
    }

    private void createDatabase(String containerName, String dbName) {
        ProcessResult result = runCommand(
                List.of("docker", "exec", containerName, "createdb", "-U", "postgres", dbName),
                null,
                COMMAND_TIMEOUT_MS,
                8 * 1024
        );
        if (result.timedOut() || result.exitCode() == null || result.exitCode() != 0) {
            throw new InternalErrorException("Failed to create SQL test database: " + outputSummary(result));
        }
    }

    private void dropDatabase(String containerName, String dbName) {
        runCommand(
                List.of("docker", "exec", containerName, "dropdb", "--if-exists", "--force", "-U", "postgres", dbName),
                null,
                COMMAND_TIMEOUT_MS,
                8 * 1024
        );
    }

    private ProcessResult runPsql(
            String containerName,
            String dbName,
            String sql,
            int timeoutMs,
            int outputLimitBytes
    ) {
        return runCommand(
                List.of(
                        "docker",
                        "exec",
                        "-i",
                        containerName,
                        "psql",
                        "-X",
                        "-v",
                        "ON_ERROR_STOP=1",
                        "-q",
                        "-A",
                        "-t",
                        "-F",
                        "\t",
                        "--pset=footer=off",
                        "-U",
                        "postgres",
                        "-d",
                        dbName
                ),
                ensureTrailingNewline(sql),
                timeoutMs,
                outputLimitBytes
        );
    }

    private void replaceContainer(WarmContainer oldContainer) {
        containers.remove(oldContainer);
        removeContainer(oldContainer.containerId());
        try {
            WarmContainer replacement = createContainer();
            containers.add(replacement);
            available.offer(replacement);
            log.info("Replaced warm SQL container {} with {}", oldContainer.containerId(), replacement.containerId());
        } catch (RuntimeException ex) {
            log.error("Failed to replace warm SQL container {}", oldContainer.containerId(), ex);
        }
    }

    private void removeContainer(String containerName) {
        runCommand(List.of("docker", "rm", "-f", containerName), null, COMMAND_TIMEOUT_MS, 8 * 1024);
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

    private String ensureTrailingNewline(String value) {
        String safeValue = value == null ? "" : value;
        return safeValue.endsWith("\n") ? safeValue : safeValue + "\n";
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
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
