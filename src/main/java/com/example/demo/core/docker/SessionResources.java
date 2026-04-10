package com.example.demo.core.docker;

import java.nio.file.Path;

/**
 * Internal representation of a prepared container session.  Holds the
 * container identifier and the host working directory that is bind‑mounted
 * into the container.  This class is intentionally simple to avoid pulling
 * unnecessary dependencies into the API layer.
 */
public class SessionResources {
    private final String containerId;
    private final Path workDir;

    public SessionResources(String containerId, Path workDir) {
        this.containerId = containerId;
        this.workDir = workDir;
    }

    public String getContainerId() {
        return containerId;
    }

    public Path getWorkDir() {
        return workDir;
    }
}