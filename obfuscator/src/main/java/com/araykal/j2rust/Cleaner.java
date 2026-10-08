package com.araykal.j2rust;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

final class Cleaner {
    static void deleteTree(Path directory) throws IOException {
        if (!Files.exists(directory)) return;
        try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (IOException exception) { throw new CleanupException(exception); }
            });
        } catch (CleanupException exception) {
            throw exception.cause;
        }
    }

    private static final class CleanupException extends RuntimeException {
        private final IOException cause;
        private CleanupException(IOException cause) { this.cause = cause; }
    }
}
