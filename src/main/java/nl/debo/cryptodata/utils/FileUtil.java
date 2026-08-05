package nl.debo.cryptodata.utils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * File-related helpers shared across the project.
 */
public final class FileUtil {

    private FileUtil() {
    }

    /**
     * Reads all lines from the first existing path in {@code candidates}. If none
     * exists, falls back to a classpath resource named {@code resourceName}
     * resolved against {@code resourceOwner}.
     *
     * @throws IOException if neither a candidate file nor the resource exists
     */
    public static List<String> readLinesWithFallback(
            Class<?> resourceOwner,
            String resourceName,
            Path... candidates
    ) throws IOException {
        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                return Files.readAllLines(candidate);
            }
        }

        var resource = resourceOwner.getResourceAsStream(resourceName);
        if (resource == null) {
            throw new IOException(resourceName + " file not found");
        }
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            return reader.lines().toList();
        }
    }
}
