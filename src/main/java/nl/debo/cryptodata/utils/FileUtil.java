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
     * Directory to resolve input/output files against: the directory containing
     * the native executable when running as a GraalVM native image, otherwise
     * the current working directory (JVM runs, IDE, {@code mvn exec}).
     */
    public static Path applicationDir() {
        if (System.getProperty("org.graalvm.nativeimage.imagecode") != null) {
            var command = ProcessHandle.current().info().command();
            if (command.isPresent()) {
                Path parent = Path.of(command.get()).toAbsolutePath().getParent();
                if (parent != null) {
                    return parent;
                }
            }
        }
        return Path.of("").toAbsolutePath();
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
