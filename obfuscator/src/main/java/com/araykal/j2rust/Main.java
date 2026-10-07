package com.araykal.j2rust;

import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
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

        @CommandLine.Parameters(index = "0", description = "Jar file to transpile")
        private File jarFile;

        @CommandLine.Parameters(index = "1", description = "Output directory")
        private String outputDirectory;

        @CommandLine.Option(names = {"-a", "--annotations"}, description = "Use annotations to ignore/include native obfuscation")
        private boolean useAnnotations;

        @CommandLine.Option(names = "--strict", description = "Fail if any selected method remains Java; write coverage.json first")
        private boolean strict;

        @Override
        public Integer call() throws Exception {
            new RustBackend().process(jarFile.toPath(), Paths.get(outputDirectory), useAnnotations, strict);
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
