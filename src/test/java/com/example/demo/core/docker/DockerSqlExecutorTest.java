package com.example.demo.core.docker;

import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.Outcome;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DockerSqlExecutorTest {

    @Test
    void supportsSqlAndPostgresqlOnly() {
        DockerSqlExecutor executor = new DockerSqlExecutor(mock(DockerSqlWarmPool.class));

        assertThat(executor.supports("sql")).isTrue();
        assertThat(executor.supports("postgresql")).isTrue();
        assertThat(executor.supports("python")).isFalse();
    }

    @Test
    void executeBatchDelegatesToWarmPool() {
        DockerSqlWarmPool warmPool = mock(DockerSqlWarmPool.class);
        DockerSqlExecutor executor = new DockerSqlExecutor(warmPool);
        List<TestInput> tests = List.of(test("sql-1"));
        ExecutionLimits limits = new ExecutionLimits();
        ExecutionPolicy policy = new ExecutionPolicy();
        TestExecutionResult result = new TestExecutionResult();
        result.setTestId("sql-1");
        result.setOutcome(Outcome.OK);
        when(warmPool.executeBatch("select 1", tests, limits, policy)).thenReturn(List.of(result));

        List<TestExecutionResult> response = executor.executeBatch("select 1", tests, limits, policy);

        assertThat(response).containsExactly(result);
        verify(warmPool).executeBatch(same("select 1"), same(tests), same(limits), same(policy));
    }

    private TestInput test(String id) {
        TestInput test = new TestInput();
        test.setId(id);
        return test;
    }
}
