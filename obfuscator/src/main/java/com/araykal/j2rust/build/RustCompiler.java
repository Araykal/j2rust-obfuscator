package com.araykal.j2rust.build;

import com.araykal.j2rust.utils.ConsoleUtil;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class RustCompiler {
    private static final String LIBRARY = "native_library";
    private static final String ZIG_URL = "https://ziglang.org/download/0.17.0/zig-x86_64-windows-0.17.0.zip";

    public List<BuildArtifact> compile(Path rustDirectory, String requestedTool, List<String> requestedTargets)
            throws Exception {
        String tool = normalizeTool(requestedTool);
        List<String> targets = tool.equals("cargo")
                ? Collections.singletonList("host") : normalizeTargets(requestedTargets);
        Path zigDirectory = null;
        if (tool.equals("cargo-zigbuild")) {
            zigDirectory = ensureZig();
            ensureCargoZigbuild(zigDirectory);
        }

        List<BuildArtifact> artifacts = new ArrayList<>();
        for (String targetAlias : targets) {
            String target = canonicalTarget(targetAlias, tool);
            ConsoleUtil.phase("Compiling native library: " + tool + " / " + target);
            ensureRustTarget(target);
            runBuild(command(tool, target), rustDirectory, zigDirectory);
            BuildArtifact artifact = artifact(target, rustDirectory);
            if (!Files.isRegularFile(artifact.path()))
                throw new IOException("Missing compiled library: " + artifact.path());
            ConsoleUtil.detail("Native library ready: " + artifact.path());
            artifacts.add(artifact);
        }
        return artifacts;
    }

    private static String normalizeTool(String value) throws IOException {
        String tool = value == null || value.trim().isEmpty() ? "cargo" : value.trim().toLowerCase();
        if (!tool.equals("cargo") && !tool.equals("cargo-zigbuild"))
            throw new IOException("Unsupported build tool: " + value + ". Use cargo or cargo-zigbuild");
        return tool;
    }

    private static List<String> normalizeTargets(List<String> values) {
        if (values == null || values.isEmpty()) return Collections.singletonList("host");
        List<String> targets = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) targets.add(value.trim());
        }
        return targets.isEmpty() ? Collections.singletonList("host") : targets;
    }

    private static String canonicalTarget(String value, String tool) throws IOException {
        String target = value.trim().toLowerCase();
        if (target.equals("host") || target.equals("windows_x86_64") || target.equals("windows_aarch64")
                || target.equals("macos_x86_64") || target.equals("macos_aarch64")
                || target.equals("linux_x86_64") || target.equals("linux_aarch64")) {
            switch (target) {
                case "host":
                    if (tool.equals("cargo-zigbuild") && System.getProperty("os.name").toLowerCase().contains("win"))
                        return "x86_64-pc-windows-gnu";
                    return "host";
                case "windows_x86_64": return tool.equals("cargo-zigbuild")
                        ? "x86_64-pc-windows-gnu" : "x86_64-pc-windows-msvc";
                case "windows_aarch64": return tool.equals("cargo-zigbuild")
                        ? "aarch64-pc-windows-gnullvm" : "aarch64-pc-windows-msvc";
                case "macos_x86_64": return "x86_64-apple-darwin";
                case "macos_aarch64": return "aarch64-apple-darwin";
                case "linux_x86_64": return "x86_64-unknown-linux-gnu";
                case "linux_aarch64": return "aarch64-unknown-linux-gnu";
                default: return target;
            }
        }
        throw new IOException("Unsupported target: " + value + ". Use host, windows_x86_64, windows_aarch64, " +
                "macos_x86_64, macos_aarch64, linux_x86_64, or linux_aarch64");
    }

    private static List<String> command(String tool, String target) {
        List<String> command = new ArrayList<>();
        command.add("cargo");
        command.add(tool.equals("cargo-zigbuild") ? "zigbuild" : "build");
        command.add("--release");
        command.add("--offline");
        if (!target.equalsIgnoreCase("host")) {
            command.add("--target");
            command.add(target);
        }
        return command;
    }

    private static BuildArtifact artifact(String target, Path rustDirectory) {
        String normalized = target.toLowerCase();
        String platform = normalized.equals("host") ? architecture(System.getProperty("os.arch")) : architecture(normalized);
        if (normalized.equals("host")) normalized = System.getProperty("os.name").toLowerCase();
        String osToken;
        String suffix;
        if (normalized.contains("windows")) {
            osToken = "windows";
            suffix = "dll";
        } else if (normalized.contains("darwin") || normalized.contains("apple")) {
            osToken = "macos";
            suffix = "dylib";
        } else {
            osToken = "linux";
            suffix = "so";
        }
        String architectureFamily = platform.equals("x64") || platform.equals("x86") ? "x86" :
                platform.startsWith("arm") ? "arm" : "raw";
        String filename = "rust-" + osToken + "-" + architectureFamily + "_" + platform + "." + suffix;
        Path release = target.equalsIgnoreCase("host")
                ? rustDirectory.resolve("target").resolve("release")
                : rustDirectory.resolve("target").resolve(target).resolve("release");
        String compiledFilename = suffix.equals("dll") ? "native_library.dll" : "libnative_library." + suffix;
        return new BuildArtifact(platform, osToken + "." + suffix, filename, release.resolve(compiledFilename));
    }

    private static void ensureCargoZigbuild(Path zigDirectory) throws Exception {
        ProcessBuilder checkBuilder = processBuilder(Collections.singletonList("cargo"),
                Paths.get("."), zigDirectory);
        checkBuilder.command("cargo", "zigbuild", "--version");
        Process check = checkBuilder.redirectErrorStream(true).start();
        drain(check.getInputStream());
        if (check.waitFor() == 0) return;
        ConsoleUtil.phase("cargo-zigbuild not found; installing cargo-zigbuild");
        Process install = processBuilder(java.util.Arrays.asList("cargo", "install", "cargo-zigbuild"),
                Paths.get("."), zigDirectory).redirectErrorStream(true).inheritIO().start();
        if (install.waitFor() != 0)
            throw new IOException("Failed to install cargo-zigbuild. Install Rust and Zig, then run: cargo install cargo-zigbuild");
    }

    private static void ensureRustTarget(String target) throws Exception {
        if (target.equalsIgnoreCase("host")) return;
        Process list = new ProcessBuilder("rustup", "target", "list", "--installed")
                .redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = list.getInputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        if (list.waitFor() != 0)
            throw new IOException("Unable to inspect Rust targets. Install rustup and retry.");
        String installed = output.toString("UTF-8");
        if (java.util.Arrays.asList(installed.split("\\R")).contains(target)) return;
        ConsoleUtil.phase("Rust target not found; installing " + target);
        Process install = new ProcessBuilder("rustup", "target", "add", target)
                .redirectErrorStream(true).inheritIO().start();
        if (install.waitFor() != 0)
            throw new IOException("Failed to install Rust target: " + target);
    }

    private static void runBuild(List<String> command, Path directory, Path zigDirectory) throws Exception {
        Process process = processBuilder(command, directory, zigDirectory).redirectErrorStream(true).start();
        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            byte[] chunk = new byte[8192];
            int count;
            while ((count = stream.read(chunk)) != -1) log.write(chunk, 0, count);
        }
        if (process.waitFor() != 0)
            throw new IOException("Rust build failed (" + String.join(" ", command) + "):\n" + log.toString("UTF-8"));
    }

    private static ProcessBuilder processBuilder(List<String> command, Path directory, Path zigDirectory) {
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile());
        if (zigDirectory != null) {
            Map<String, String> environment = builder.environment();
            String path = environment.get("PATH");
            environment.put("PATH", zigDirectory.toString() + java.io.File.pathSeparator + (path == null ? "" : path));
            environment.put("ZIG", zigDirectory.resolve("zig.exe").toAbsolutePath().toString());
        }
        return builder;
    }

    private static Path ensureZig() throws Exception {
        Path directory = applicationDirectory().resolve("zig");
        Path executable = directory.resolve("zig.exe");
        if (Files.isRegularFile(executable)) return directory;
        if (!System.getProperty("os.name").toLowerCase().contains("win"))
            throw new IOException("Zig is missing. Install Zig and add it to PATH before using cargo-zigbuild.");

        Path archive = Files.createTempFile("j2rust-zig-", ".zip");
        try {
            download(ZIG_URL, archive);
            Files.createDirectories(directory);
            extractZip(archive, directory);
        } finally {
            Files.deleteIfExists(archive);
        }
        if (!Files.isRegularFile(executable))
            throw new IOException("Downloaded Zig archive did not contain zig.exe: " + directory);
        ConsoleUtil.phase("Using local Zig: " + executable);
        return directory;
    }

    private static Path applicationDirectory() {
        try {
            Path location = Paths.get(RustCompiler.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location) ? location.getParent() : location;
        } catch (Exception ignored) {
            return Paths.get(System.getProperty("user.dir"));
        }
    }

    private static void download(String address, Path destination) throws IOException {
        ConsoleUtil.phase("Downloading Zig 0.17.0");
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        int length = connection.getContentLength();
        long total = 0;
        try (InputStream input = connection.getInputStream(); java.io.OutputStream output = Files.newOutputStream(destination)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
                total += count;
                String progress = length > 0 ? String.format("%3d%%", Math.min(100, total * 100 / length))
                        : String.format("%d KB", total / 1024);
                System.out.print("\r[J2Rust] Downloading Zig: " + progress);
            }
        } finally {
            System.out.println();
            connection.disconnect();
        }
    }

    private static void extractZip(Path archive, Path destination) throws IOException {
        try (ZipInputStream input = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                int separator = name.indexOf('/');
                String relative = separator >= 0 ? name.substring(separator + 1) : name;
                if (relative.isEmpty()) continue;
                Path target = destination.resolve(relative).normalize();
                if (!target.startsWith(destination.normalize())) throw new IOException("Unsafe Zig archive entry: " + name);
                if (entry.isDirectory()) Files.createDirectories(target);
                else {
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void drain(InputStream stream) throws IOException {
        byte[] buffer = new byte[1024];
        while (stream.read(buffer) != -1) { }
        stream.close();
    }

    private static String architecture(String value) {
        String normalized = value.toLowerCase();
        if (normalized.contains("x86_64") || normalized.contains("amd64") || normalized.startsWith("x86-64")) return "x64";
        if (normalized.contains("aarch64") || normalized.contains("arm64")) return "arm64";
        if (normalized.contains("i686") || normalized.contains("i586") || normalized.equals("x86")) return "x86";
        if (normalized.contains("armv7") || normalized.equals("arm")) return "arm32";
        return "raw" + normalized.replace('-', '_');
    }
}
