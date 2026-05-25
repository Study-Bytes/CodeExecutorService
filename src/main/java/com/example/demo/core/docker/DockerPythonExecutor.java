package com.example.demo.core.docker;

import com.example.demo.api.dto.*;
import com.example.demo.core.validation.InternalErrorException;
import com.example.demo.core.docker.SessionResources;
import com.example.demo.core.executor.LanguageExecutor;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Component
public class DockerPythonExecutor implements LanguageExecutor {

    // Для MVP фиксируем образ. Можно вынести в application.properties.
    private static final String PYTHON_IMAGE = "python:3.12-alpine";

    // Виртуальные потоки удобны (Java 21+). Если будут проблемы, можно заменить на cachedThreadPool.
    private final ExecutorService ioExecutor = Executors.newVirtualThreadPerTaskExecutor();

    @Override
    public boolean supports(String language) {
        return "python".equals(language);
    }

    /**
     * Create a new long‑lived container session.  The session mounts a
     * temporary working directory containing the user code and starts the
     * container in an idle state (tail -f /dev/null).  The caller is
     * responsible for closing the session via {@link #closeSession(SessionResources)}.
     *
     * @param code   user source code to write to main.py
     * @param limits execution limits (for memory limits only)
     * @param policy execution policy (controls networking, readOnly FS, etc.)
     * @return resources describing the created session (container id and working dir)
     */
    public SessionResources createSession(String code,
                                          ExecutionLimits limits,
                                          ExecutionPolicy policy) {
        Path workDir;
        try {
            workDir = Files.createTempDirectory("exec-session-" + UUID.randomUUID());
            Path mainPy = workDir.resolve("main.py");
            Files.writeString(mainPy, code, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new InternalErrorException("Failed to prepare working directory: " + e.getMessage(), e);
        }

        // Build docker create command
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("create");
        cmd.add("--name");
        // give the container a unique name
        String containerName = "exec-" + UUID.randomUUID();
        cmd.add(containerName);

        if (Boolean.TRUE.equals(policy.getNetworkDisabled())) {
            cmd.add("--network");
            cmd.add("none");
        }

        // memory limit per container
        if (limits.getMemoryLimitMb() != null && limits.getMemoryLimitMb() > 0) {
            cmd.add("--memory");
            cmd.add(limits.getMemoryLimitMb() + "m");
        }
        // CPU and pids defaults
        cmd.add("--cpus");
        cmd.add("1");
        cmd.add("--pids-limit");
        cmd.add("64");

        if (Boolean.TRUE.equals(policy.getReadOnlyFs())) {
            cmd.add("--read-only");
            cmd.add("--tmpfs");
            cmd.add("/tmp:rw,nosuid,nodev,noexec,size=64m");
        }

        // Bind mount code directory as read‑only
        cmd.add("--mount");
        cmd.add("type=bind,source=" + workDir.toAbsolutePath() + ",target=/work,readonly");
        cmd.add("-w");
        cmd.add("/work");

        // Image and command: keep container running idle
        cmd.add(PYTHON_IMAGE);
        cmd.add("tail");
        cmd.add("-f");
        cmd.add("/dev/null");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();
            // read stdout/stderr to avoid blocking
            CompletableFuture<?> outF = CompletableFuture.runAsync(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    while (reader.readLine() != null) {
                        // ignore
                    }
                } catch (IOException ignore) {}
            }, ioExecutor);
            CompletableFuture<?> errF = CompletableFuture.runAsync(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(p.getErrorStream()))) {
                    while (reader.readLine() != null) {
                        // ignore
                    }
                } catch (IOException ignore) {}
            }, ioExecutor);
            int exit = p.waitFor();
            if (exit != 0) {
                // create failed
                throw new InternalErrorException("Failed to create docker container, exit code: " + exit);
            }
            // container name equals id for create
            String containerId = containerName;
            // start the container
            ProcessBuilder startPb = new ProcessBuilder("docker", "start", containerId);
            Process startProc = startPb.start();
            int startExit = startProc.waitFor();
            if (startExit != 0) {
                throw new InternalErrorException("Failed to start docker container, exit code: " + startExit);
            }
            return new SessionResources(containerId, workDir);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalErrorException("Failed to create session: " + e.getMessage(), e);
        }
    }

    /**
     * Execute a single test inside an existing session container.  This method
     * launches a new process inside the container using {@code docker exec}
     * and applies per‑test timeout and output limits.  The memory limit is
     * enforced at container creation time.
     *
     * @param session resources describing the running container and workDir
     * @param test    test input (id, stdin, timeout)
     * @param limits  global execution limits (used for default time limit and output limit)
     * @return result of the test execution
     */
    public TestExecutionResult executeInSession(SessionResources session,
                                                TestInput test,
                                                ExecutionLimits limits) {
        long startNs = System.nanoTime();
        int timeLimitMs = test.getTimeoutMs() != null ? test.getTimeoutMs() : limits.getTimeLimitMs();
        int outputLimitBytes = Math.max(1, limits.getOutputLimitKb()) * 1024;

        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("exec");
        // working directory inside container
        cmd.add("-w");
        cmd.add("/work");
        cmd.add("-i");
        cmd.add(session.getContainerId());
        cmd.add("python3");
        cmd.add("main.py");

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();

            // send stdin
            String input = test.getInput() != null ? test.getInput() : "";
            try (OutputStream os = p.getOutputStream()) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            CompletableFuture<OutputBlob> stdoutF = readLimited(p.getInputStream(), outputLimitBytes);
            CompletableFuture<OutputBlob> stderrF = readLimited(p.getErrorStream(), outputLimitBytes);

            boolean finished = p.waitFor(timeLimitMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                // kill exec process; the process inside container should be terminated as well
                p.destroyForcibly();
                TestExecutionResult r = new TestExecutionResult();
                r.setTestId(test.getId());
                r.setOutcome(Outcome.TIMEOUT);
                r.setExitCode(null);
                r.setStdout(safeJoin(stdoutF));
                r.setStderr(safeJoin(stderrF));
                r.setDurationMs(Duration.ofNanos(System.nanoTime() - startNs).toMillis());
                r.setMemoryMb(null);
                return r;
            }

            int exitCode = p.exitValue();
            OutputBlob stdout = safeJoin(stdoutF);
            OutputBlob stderr = safeJoin(stderrF);
            Outcome outcome;
            if (exitCode == 0) {
                outcome = Outcome.OK;
            } else if (exitCode == 137) {
                outcome = Outcome.MEMORY_LIMIT;
            } else {
                outcome = Outcome.RUNTIME_ERROR;
            }
            TestExecutionResult r = new TestExecutionResult();
            r.setTestId(test.getId());
            r.setOutcome(outcome);
            r.setExitCode(exitCode);
            r.setStdout(stdout);
            r.setStderr(stderr);
            r.setDurationMs(Duration.ofNanos(System.nanoTime() - startNs).toMillis());
            r.setMemoryMb(null);
            return r;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalErrorException("Failed to execute test in session: " + e.getMessage(), e);
        }
    }

    /**
     * Close a session by stopping and removing the container and deleting the
     * working directory.  This method suppresses secondary IO exceptions
     * during cleanup.
     *
     * @param session session resources returned by {@link #createSession}
     */
    public void closeSession(SessionResources session) {
        if (session == null) return;
        String containerId = session.getContainerId();
        Path workDir = session.getWorkDir();
        // stop the container if it's still running
        try {
            new ProcessBuilder("docker", "stop", containerId).start().waitFor();
        } catch (Exception ignore) {
        }
        // remove the container
        try {
            new ProcessBuilder("docker", "rm", "-f", containerId).start().waitFor();
        } catch (Exception ignore) {
        }
        // delete working directory
        try {
            deleteRecursive(workDir);
        } catch (Exception ignore) {
        }
    }

    /**
     * Execute a batch of tests inside a single container.  A new session is
     * created, tests are executed sequentially via exec and the session is
     * destroyed on completion.  If a test produces a technical error
     * (TIMEOUT, MEMORY_LIMIT, RUNTIME_ERROR, INTERNAL_ERROR) then
     * subsequent tests are not executed; instead placeholder results are
     * returned with the same outcome.  This avoids wasting time on a batch
     * once an unrecoverable error is encountered.
     *
     * @param code   user code to execute
     * @param tests  list of tests to run
     * @param limits execution limits
     * @param policy execution policy
     * @return list of test results in the same order as input tests
     */
    public List<TestExecutionResult> executeBatchSingleContainer(String code,
                                                                 List<TestInput> tests,
                                                                 ExecutionLimits limits,
                                                                 ExecutionPolicy policy) {
        SessionResources session = createSession(code, limits, policy);
        List<TestExecutionResult> results = new ArrayList<>(tests.size());
        boolean encounteredError = false;
        Outcome errorOutcome = null;
        try {
            for (int i = 0; i < tests.size(); i++) {
                TestInput test = tests.get(i);
                if (!encounteredError) {
                    TestExecutionResult res = executeInSession(session, test, limits);
                    results.add(res);
                    if (res.getOutcome() != Outcome.OK) {
                        encounteredError = true;
                        errorOutcome = res.getOutcome();
                    }
                } else {
                    // skip execution, return placeholder with same outcome
                    TestExecutionResult placeholder = new TestExecutionResult();
                    placeholder.setTestId(test.getId());
                    placeholder.setOutcome(errorOutcome != null ? errorOutcome : Outcome.RUNTIME_ERROR);
                    placeholder.setExitCode(null);
                    placeholder.setStdout(new OutputBlob("", false));
                    placeholder.setStderr(new OutputBlob("", false));
                    placeholder.setDurationMs(null);
                    placeholder.setMemoryMb(null);
                    results.add(placeholder);
                }
            }
        } finally {
            closeSession(session);
        }
        return results;
    }
    @Override
    public List<TestExecutionResult> executeBatch(String code,
                                                 List<TestInput> tests,
                                                 ExecutionLimits limits,
                                                 ExecutionPolicy policy) {
        return executeBatchSingleContainer(code, tests, limits, policy);
    }

    private TestExecutionResult executeSingle(String code,
                                             TestInput test,
                                             ExecutionLimits limits,
                                             ExecutionPolicy policy) {
        long startNs = System.nanoTime();

        Path workDir = null;
        try {
            workDir = Files.createTempDirectory("exec-" + UUID.randomUUID());
            Path mainPy = workDir.resolve("main.py");
            Files.writeString(mainPy, code, StandardCharsets.UTF_8);

            int timeLimitMs = test.getTimeoutMs() != null ? test.getTimeoutMs() : limits.getTimeLimitMs();
            int outputLimitBytes = Math.max(1, limits.getOutputLimitKb()) * 1024;

            List<String> cmd = buildDockerCommand(workDir, policy, limits);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            Process p = pb.start();

            // stdin
            String input = test.getInput() != null ? test.getInput() : "";
            try (OutputStream os = p.getOutputStream()) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            CompletableFuture<OutputBlob> stdoutF = readLimited(p.getInputStream(), outputLimitBytes);
            CompletableFuture<OutputBlob> stderrF = readLimited(p.getErrorStream(), outputLimitBytes);

            boolean finished = p.waitFor(timeLimitMs, TimeUnit.MILLISECONDS);
            if (!finished) {
                p.destroyForcibly();
                TestExecutionResult r = new TestExecutionResult();
                r.setTestId(test.getId());
                r.setOutcome(Outcome.TIMEOUT);
                r.setExitCode(null);
                r.setStdout(safeJoin(stdoutF));
                r.setStderr(safeJoin(stderrF));
                r.setDurationMs(Duration.ofNanos(System.nanoTime() - startNs).toMillis());
                r.setMemoryMb(null);
                return r;
            }

            int exitCode = p.exitValue();
            OutputBlob stdout = safeJoin(stdoutF);
            OutputBlob stderr = safeJoin(stderrF);

            Outcome outcome;
            if (exitCode == 0) {
                outcome = Outcome.OK;
            } else if (exitCode == 137) {
                // Часто 137 = SIGKILL (в т.ч. OOM). Для MVP так.
                outcome = Outcome.MEMORY_LIMIT;
            } else {
                outcome = Outcome.RUNTIME_ERROR;
            }

            TestExecutionResult r = new TestExecutionResult();
            r.setTestId(test.getId());
            r.setOutcome(outcome);
            r.setExitCode(exitCode);
            r.setStdout(stdout);
            r.setStderr(stderr);
            r.setDurationMs(Duration.ofNanos(System.nanoTime() - startNs).toMillis());
            r.setMemoryMb(null); // точный peak memory в MVP не считаем
            return r;

        } catch (IOException e) {
            throw new InternalErrorException("Failed to start docker. Is Docker installed and running? Details: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InternalErrorException("Execution interrupted", e);
        } finally {
            if (workDir != null) {
                try {
                    deleteRecursive(workDir);
                } catch (Exception ignore) {
                }
            }
        }
    }

    private List<String> buildDockerCommand(Path workDir, ExecutionPolicy policy, ExecutionLimits limits) {
        List<String> cmd = new ArrayList<>();
        cmd.add("docker");
        cmd.add("run");
        cmd.add("--rm");
        cmd.add("-i");

        if (Boolean.TRUE.equals(policy.getNetworkDisabled())) {
            cmd.add("--network");
            cmd.add("none");
        }

        // memory
        if (limits.getMemoryLimitMb() != null && limits.getMemoryLimitMb() > 0) {
            cmd.add("--memory");
            cmd.add(limits.getMemoryLimitMb() + "m");
        }

        // CPU и процессы — простые дефолты для MVP
        cmd.add("--cpus");
        cmd.add("1");
        cmd.add("--pids-limit");
        cmd.add("64");

        if (Boolean.TRUE.equals(policy.getReadOnlyFs())) {
            cmd.add("--read-only");
            cmd.add("--tmpfs");
            cmd.add("/tmp:rw,nosuid,nodev,noexec,size=64m");
        }

        // Bind mount кода read-only
        cmd.add("--mount");
        cmd.add("type=bind,source=" + workDir.toAbsolutePath() + ",target=/work,readonly");
        cmd.add("-w");
        cmd.add("/work");

        cmd.add(PYTHON_IMAGE);
        cmd.add("python3");
        cmd.add("main.py");
        return cmd;
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

    private OutputBlob safeJoin(CompletableFuture<OutputBlob> f) {
        try {
            return f.get(200, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return new OutputBlob("", false);
        }
    }

    private void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var s = Files.walk(dir)) {
            s.sorted((a, b) -> b.compareTo(a))
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignore) {
                        }
                    });
        }
    }
}
