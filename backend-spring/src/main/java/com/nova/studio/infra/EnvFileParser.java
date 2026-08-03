package com.nova.studio.infra;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Port of the Node backend's {@code parseEnvFile} ({@code backend/server.js}):
 * parses a {@code KEY=VALUE} env file, skipping blank lines and {@code #}
 * comments, stripping surrounding quotes. Used for the runtime hot-read of the
 * git-ignored {@code .env} (1s TTL, see {@link RuntimeEnv}).
 */
public final class EnvFileParser {

    private EnvFileParser() {
    }

    /** Parses the given file into a map; returns an empty map when the file is absent. */
    public static Map<String, String> parse(Path filePath) {
        Map<String, String> values = new HashMap<>();
        if (filePath == null || !Files.exists(filePath)) {
            return values;
        }
        try {
            String content = Files.readString(filePath, StandardCharsets.UTF_8);
            for (String rawLine : content.split("\\r?\\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int sep = line.indexOf('=');
                if (sep <= 0) {
                    continue;
                }
                String key = line.substring(0, sep).trim();
                String rawValue = line.substring(sep + 1).trim();
                String value = rawValue;
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                values.put(key, value);
            }
        } catch (IOException ignored) {
            // Missing/unreadable env file → treat as empty (matches Node behavior).
        }
        return values;
    }
}
