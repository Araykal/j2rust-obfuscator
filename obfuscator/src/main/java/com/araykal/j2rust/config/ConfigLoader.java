package com.araykal.j2rust.config;

import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import org.tomlj.Toml;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class ConfigLoader {
    private ConfigLoader() { }

    public static J2RustConfig load(Path path) throws IOException {
        TomlParseResult document = Toml.parse(path);
        if (document.hasErrors()) throw new IOException("Invalid TOML: " + document.errors());
        J2RustConfig schema = readSchema(document);
        schema.setBaseDirectory(path.getParent() == null ? java.nio.file.Paths.get(".") : path.getParent());
        if (schema.annotations) { schema.include = new ArrayList<>(); schema.exclude = new ArrayList<>(); }
        else if (!schema.include.isEmpty()) schema.exclude = new ArrayList<>();
        return schema;
    }

    public static void createTemplate(Path path) throws IOException {
        ConfigTemplate.write(path);
    }

    private static J2RustConfig readSchema(TomlParseResult document) throws IOException {
        J2RustConfig schema = new J2RustConfig();
        for (Field field : J2RustConfig.class.getDeclaredFields()) {
            ConfigOption option = field.getAnnotation(ConfigOption.class);
            if (option == null) continue;
            Object parsed = readValue(document, option, field.getType());
            try {
                field.setAccessible(true);
                field.set(schema, parsed);
            } catch (IllegalAccessException exception) {
                throw new IOException("Cannot load config field: " + field.getName(), exception);
            }
        }
        return schema;
    }

    private static Object readValue(TomlParseResult document, ConfigOption option, Class<?> type) throws IOException {
        TomlTable table = option.section().isEmpty() ? document : document.getTable(option.section());
        Object value = table == null ? null : table.get(option.key());
        if (value == null) value = parseDefault(option.defaultValue(), type);
        if (type == String.class && (!(value instanceof String) || ((String) value).trim().isEmpty()))
            throw new IOException("Missing required config key: " + option.section() + "." + option.key());
        if (type == boolean.class && !(value instanceof Boolean))
            throw new IOException(option.key() + " must be a boolean");
        if (List.class.isAssignableFrom(type)) {
            if (!(value instanceof org.tomlj.TomlArray)) throw new IOException(option.key() + " must be an array");
            List<String> result = new ArrayList<>();
            for (Object item : ((org.tomlj.TomlArray) value).toList()) {
                if (!(item instanceof String) || ((String) item).trim().isEmpty()) throw new IOException(option.key() + " must contain strings");
                result.add((String) item);
            }
            return result;
        }
        return value;
    }

    private static Object parseDefault(String value, Class<?> type) {
        if (type == boolean.class) return Boolean.valueOf(value);
        if (List.class.isAssignableFrom(type)) return new ArrayList<String>();
        return value.substring(1, value.length() - 1);
    }

}
