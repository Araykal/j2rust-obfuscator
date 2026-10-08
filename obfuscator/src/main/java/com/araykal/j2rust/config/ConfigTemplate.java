package com.araykal.j2rust.config;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class ConfigTemplate {
    private ConfigTemplate() {
    }

    static void write(Path path) throws IOException {
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        Map<String, StringBuilder> sections = new LinkedHashMap<>();
        for (Field field : J2RustConfig.class.getDeclaredFields()) {
            ConfigOption option = field.getAnnotation(ConfigOption.class);
            if (option == null) continue;
            StringBuilder section = sections.computeIfAbsent(option.section(), ignored -> new StringBuilder());
            for (String line : option.description().split("\\R", -1))
                section.append("# ").append(line).append('\n');
            section.append(option.key()).append(" = ").append(option.defaultValue()).append("\n\n");
        }
        StringBuilder output = new StringBuilder("# J2Rust configuration \n");
        for (Map.Entry<String, StringBuilder> entry : sections.entrySet()) {
            if (!entry.getKey().isEmpty()) output.append('[').append(entry.getKey()).append("]\n");
            output.append(entry.getValue()).append('\n');
        }
        Files.write(path, output.toString().getBytes(StandardCharsets.UTF_8));
    }
}
