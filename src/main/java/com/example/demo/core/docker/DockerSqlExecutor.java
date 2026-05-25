package com.example.demo.core.docker;

import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;
import com.example.demo.core.executor.LanguageExecutor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class DockerSqlExecutor implements LanguageExecutor {

    private final DockerSqlWarmPool dockerSqlWarmPool;

    public DockerSqlExecutor(DockerSqlWarmPool dockerSqlWarmPool) {
        this.dockerSqlWarmPool = dockerSqlWarmPool;
    }

    @Override
    public boolean supports(String language) {
        return "sql".equals(language) || "postgresql".equals(language);
    }

    @Override
    public List<TestExecutionResult> executeBatch(
            String code,
            List<TestInput> tests,
            ExecutionLimits limits,
            ExecutionPolicy policy
    ) {
        return dockerSqlWarmPool.executeBatch(code, tests, limits, policy);
    }
}
