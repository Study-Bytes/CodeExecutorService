package com.example.demo.core.executor;

import com.example.demo.api.dto.ExecutionLimits;
import com.example.demo.api.dto.ExecutionPolicy;
import com.example.demo.api.dto.TestExecutionResult;
import com.example.demo.api.dto.TestInput;
import com.example.demo.core.validation.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LanguageExecutorRegistryTest {

    @Test
    void normalizeHandlesNullTrimAndLowercase() {
        LanguageExecutorRegistry registry = new LanguageExecutorRegistry(List.of());

        assertThat(registry.normalize(null)).isEmpty();
        assertThat(registry.normalize("  PostgreSQL  ")).isEqualTo("postgresql");
    }

    @Test
    void resolveReturnsMatchingExecutorWithNormalizedLanguage() {
        LanguageExecutor sqlExecutor = executor("sql");
        LanguageExecutorRegistry registry = new LanguageExecutorRegistry(List.of(sqlExecutor));

        LanguageExecutor resolved = registry.resolve("  SQL  ");

        assertThat(resolved).isSameAs(sqlExecutor);
    }

    @Test
    void resolveThrowsBadRequestForUnsupportedLanguage() {
        LanguageExecutorRegistry registry = new LanguageExecutorRegistry(List.of(executor("python")));

        assertThatThrownBy(() -> registry.resolve("ruby"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Unsupported language: ruby");
    }

    @Test
    void resolveUsesFirstMatchingExecutor() {
        LanguageExecutor first = executor("sql");
        LanguageExecutor second = executor("sql");
        LanguageExecutorRegistry registry = new LanguageExecutorRegistry(List.of(first, second));

        assertThat(registry.resolve("sql")).isSameAs(first);
    }

    private LanguageExecutor executor(String supportedLanguage) {
        return new LanguageExecutor() {
            @Override
            public boolean supports(String language) {
                return supportedLanguage.equals(language);
            }

            @Override
            public List<TestExecutionResult> executeBatch(
                    String code,
                    List<TestInput> tests,
                    ExecutionLimits limits,
                    ExecutionPolicy policy
            ) {
                return List.of();
            }
        };
    }
}
