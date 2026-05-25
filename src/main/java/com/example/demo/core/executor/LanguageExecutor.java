package com.example.demo.core.executor;

import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;

import java.util.List;

public interface LanguageExecutor {

    boolean supports(String language);

    List<TestExecutionResult> executeBatch(
            String code,
            List<TestInput> tests,
            ExecutionLimits limits,
            ExecutionPolicy policy
    );
}
