package com.araykal.j2rust.config;

import java.util.List;
import java.util.ArrayList;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class J2RustConfig {
    @ConfigOption(section = "", key = "input", description = "Input JAR path, relative to this file.", defaultValue = "\"test.jar\"")
    public String input;

    @ConfigOption(section = "", key = "output", description = "Output transformed JAR path, relative to this file.", defaultValue = "\"test/test-obf.jar\"")
    public String output;

    @ConfigOption(section = "selection", key = "annotations", description = "Use @Native/@NotNative. This takes priority over include and exclude.", defaultValue = "false")
    public boolean annotations;

    @ConfigOption(section = "selection", key = "include", description = "Only convert matching classes or methods.\nExamples:\n  pack.Main       = all methods in one class\n  pack.*          = all classes directly in pack (not subpackages)\n  pack.**         = all classes in pack and its subpackages\n  pack.Main.*     = all methods in pack.Main\n  pack.Main#main  = one method\n  pack.Main#add(II)I = one method with descriptor.", defaultValue = "[]")
    public List<String> include = new ArrayList<>();

    @ConfigOption(section = "selection", key = "exclude", description = "Skip matching classes or methods when include is empty.\nThe same class, package, recursive-package, and method syntax as include is supported.", defaultValue = "[\"pack.tests.basics.resource.*\",\"pack.Main#main\"]")
    public List<String> exclude = new ArrayList<>();

    @ConfigOption(section = "options", key = "strict", description = "Fail when selected methods remain Java.", defaultValue = "false")
    public boolean strict;

    @ConfigOption(section = "options", key = "no_color", description = "Disable ANSI terminal colors.", defaultValue = "false")
    public boolean noColor;

    @ConfigOption(section = "options", key = "clear", description = "Build in a temporary directory, keep only the configured output JAR, then delete generated files.", defaultValue = "false")
    public boolean clear;

    @ConfigOption(section = "build", key = "tool", description = "Rust build tool: cargo or cargo-zigbuild.", defaultValue = "\"cargo\"")
    public String buildTool;

    @ConfigOption(section = "build", key = "targets", description = "Targets to compile when tool = cargo-zigbuild (cargo always uses host):\n  host\n  windows_x86_64\n  windows_aarch64\n  macos_x86_64\n  macos_aarch64\n  linux_x86_64\n  linux_aarch64.\nWith cargo-zigbuild, Windows aliases use GNU/GNULVM targets.", defaultValue = "[\"windows_x86_64\",\"windows_aarch64\",\"linux_x86_64\",\"linux_aarch64\"]")
    public List<String> buildTargets = new ArrayList<>();

    private Path baseDirectory = Paths.get(".");

    public J2RustConfig() {
    }

    void setBaseDirectory(Path baseDirectory) {
        this.baseDirectory = baseDirectory;
    }

    public Path inputPath() {
        return baseDirectory.resolve(input).normalize();
    }

    public Path outputPath() {
        return baseDirectory.resolve(output).normalize();
    }
}
