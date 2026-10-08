package com.araykal.j2rust;

import com.araykal.j2rust.utils.ConsoleUtil;
import com.araykal.j2rust.config.ConfigLoader;
import com.araykal.j2rust.config.J2RustConfig;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.concurrent.Callable;

public class Main {

    private static String VERSION = "git-00000000";

    public static void main(String[] args) throws IOException {
        System.exit(new CommandLine(new Runner())
                .setCaseInsensitiveEnumValuesAllowed(true).execute(args));
    }

    @CommandLine.Command(name = "j2rust", mixinStandardHelpOptions = true,
            versionProvider = VersionProvider.class,
            description = "Transpiles a JAR and generates an output JAR with a native project")
    private static class Runner implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", arity = "0..1", description = "Jar file to transpile (omit with --config)")
        private File jarFile;

        @CommandLine.Parameters(index = "1", arity = "0..1", description = "Output directory (omit with --config)")
        private String outputDirectory;

        @CommandLine.Option(names = "--config", description = "TOML configuration file")
        private File configFile;

        @CommandLine.Option(names = {"-a", "--annotations"}, description = "Use annotations to ignore/include native obfuscation")
        private boolean useAnnotations;

        @CommandLine.Option(names = "--strict", description = "Fail if any selected method remains Java; write coverage.json first")
        private boolean strict;

        @CommandLine.Option(names = "--no-color", description = "Disable ANSI colors and the colored terminal output")
        private boolean noColor;

        @Override
        public Integer call() throws Exception {
            J2RustConfig config;
            if (configFile != null) {
                ConsoleUtil.setColorEnabled(!noColor);
                if (!Files.exists(configFile.toPath())) {
                    ConfigLoader.createTemplate(configFile.toPath());
                    ConsoleUtil.banner(VERSION);
                    ConsoleUtil.phase("Configuration template created: " + configFile);
                    ConsoleUtil.phase("Edit the file and run the same command again.");
                    return 0;
                }
                config = ConfigLoader.load(configFile.toPath());
            } else {
                if (jarFile == null || outputDirectory == null)
                    throw new CommandLine.ParameterException(new CommandLine(this), "input.jar and output directory are required without --config");
                config = new J2RustConfig();
                config.input = jarFile.toPath().toString();
                config.output = Paths.get(outputDirectory).resolve(jarFile.getName()).toString();
                config.annotations = useAnnotations;
                config.strict = strict;
                config.noColor = noColor;
            }
            Path outputPath = config.outputPath();
            Path outputDir = outputPath.getParent() == null ? Paths.get(".") : outputPath.getParent();
            if (config.inputPath().toAbsolutePath().normalize().equals(outputPath.toAbsolutePath().normalize()))
                throw new CommandLine.ParameterException(new CommandLine(this), "Input and output JAR paths must differ");
            ConsoleUtil.setColorEnabled(!config.noColor);
            ConsoleUtil.banner(VERSION);
            ConsoleUtil.phase("Analyzing " + config.inputPath().getFileName());
            Path buildDir = outputDir;
            if (config.clear) {
                Files.createDirectories(outputDir);
                Path parent = outputDir.toAbsolutePath().getParent();
                if (parent == null) parent = Paths.get(".").toAbsolutePath().normalize();
                buildDir = Files.createTempDirectory(parent, ".j2rust-build-");
            }
            try {
                new RustBackend().process(config.inputPath(), buildDir, config.annotations, config.strict,
                        config.include, config.exclude, outputPath.getFileName().toString(), false,
                        config.buildTool, config.buildTargets);
                if (config.clear) {
                    ConsoleUtil.phase("Moving final JAR and clearing generated files");
                    Files.createDirectories(outputPath.toAbsolutePath().getParent() == null
                            ? Paths.get(".") : outputPath.toAbsolutePath().getParent());
                    Path stagedJar = buildDir.resolve(outputPath.getFileName().toString());
                    Files.move(stagedJar, outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (config.clear) Cleaner.deleteTree(buildDir);
            }
            ConsoleUtil.phase("Finished: " + outputPath);
            return 0;
        }
    }

    private static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{VERSION};
        }
    }
}
