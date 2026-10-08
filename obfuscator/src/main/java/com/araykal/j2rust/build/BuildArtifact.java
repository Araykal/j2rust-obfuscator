package com.araykal.j2rust.build;

import java.nio.file.Path;

public final class BuildArtifact {
    private final String platform;
    private final String extension;
    private final String filename;
    private final Path path;

    public BuildArtifact(String platform, String extension, String filename, Path path) {
        this.platform = platform;
        this.extension = extension;
        this.filename = filename;
        this.path = path;
    }

    public String platform() { return platform; }
    public String extension() { return extension; }
    public String filename() { return filename; }
    public Path path() { return path; }
}
