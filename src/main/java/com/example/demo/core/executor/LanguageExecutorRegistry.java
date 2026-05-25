package com.example.demo.core.executor;

import com.example.demo.core.validation.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LanguageExecutorRegistry {

    private final List<LanguageExecutor> executors;

    public LanguageExecutorRegistry(List<LanguageExecutor> executors) {
        this.executors = executors;
    }

    public LanguageExecutor resolve(String language) {
        String normalized = normalize(language);
        return executors.stream()
                .filter(executor -> executor.supports(normalized))
                .findFirst()
                .orElseThrow(() -> new BadRequestException("Unsupported language: " + language));
    }

    public String normalize(String language) {
        return language == null ? "" : language.trim().toLowerCase();
    }
}
