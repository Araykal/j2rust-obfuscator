package com.araykal.j2rust.report;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CoverageReport {
    private final List<Entry> entries = new ArrayList<>();

    public void add(String owner, String name, String descriptor, String status, String reason) {
        entries.add(new Entry(owner, name, descriptor, status, reason));
    }

    public void write(Path path) throws IOException {
        int converted = 0;
        int kept = 0;
        int excluded = 0;
        for (Entry entry : entries) {
            if (entry.status.startsWith("rust-")) converted++;
            else if (entry.status.equals("java-retained")) kept++;
            else excluded++;
        }
        StringBuilder json = new StringBuilder("{\n  \"schemaVersion\": 1,\n  \"converted\": ")
                .append(converted).append(",\n  \"keptJava\": ").append(kept)
                .append(",\n  \"excluded\": ").append(excluded).append(",\n  \"methods\": [\n");
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            json.append("    {\"class\": ").append(quote(entry.owner))
                    .append(", \"method\": ").append(quote(entry.name))
                    .append(", \"descriptor\": ").append(quote(entry.descriptor))
                    .append(", \"status\": ").append(quote(entry.status))
                    .append(", \"reason\": ").append(quote(entry.reason)).append('}');
            if (index + 1 < entries.size()) json.append(',');
            json.append('\n');
        }
        json.append("  ]\n}\n");
        Files.write(path, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String quote(String value) {
        StringBuilder escaped = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') escaped.append('\\').append(character);
            else if (character == '\n') escaped.append("\\n");
            else if (character == '\r') escaped.append("\\r");
            else if (character == '\t') escaped.append("\\t");
            else if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
            else escaped.append(character);
        }
        return escaped.append('"').toString();
    }

    private static final class Entry {
        final String owner;
        final String name;
        final String descriptor;
        final String status;
        final String reason;

        Entry(String owner, String name, String descriptor, String status, String reason) {
            this.owner = owner;
            this.name = name;
            this.descriptor = descriptor;
            this.status = status;
            this.reason = reason;
        }
    }
}
