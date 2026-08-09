package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.FileUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bitvavo API key and secret, loaded from a {@code bitvavo.properties} file
 * (gitignored) next to the binary or in the repo root. A key with read-only
 * permissions is sufficient for the balance report.
 */
public record BitvavoCredentials(String apiKey, String apiSecret) {

    private static final String FILE_NAME = "bitvavo.properties";

    private static final String HELP = """
            Bitvavo credentials not found or incomplete. Create %s in the repo \
            root (it is gitignored) with two lines:
            apiKey=<your Bitvavo API key>
            apiSecret=<your Bitvavo API secret>
            Create the key at https://account.bitvavo.com/user/api with read-only permission.\
            """.formatted(FILE_NAME);

    /**
     * Loads the credentials, trying the application directory, the repo
     * resources directory and the working directory, falling back to a
     * classpath resource — the same lookup the symbol files use.
     *
     * @throws IOException if no file exists or a key is missing/blank
     */
    public static BitvavoCredentials load() throws IOException {
        List<String> lines;
        try {
            Path appDir = FileUtil.applicationDir();
            lines = FileUtil.readLinesWithFallback(
                    BitvavoCredentials.class,
                    FILE_NAME,
                    appDir.resolve(FILE_NAME),
                    Path.of("src/main/resources/nl/debo/cryptodata/" + FILE_NAME),
                    Path.of(FILE_NAME)
            );
        } catch (IOException e) {
            throw new IOException(HELP, e);
        }

        Map<String, String> values = new HashMap<>();
        for (String line : lines) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.isEmpty() || trimmed.startsWith("#") || eq < 0) {
                continue;
            }
            values.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }

        String apiKey = values.getOrDefault("apiKey", "");
        String apiSecret = values.getOrDefault("apiSecret", "");
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            throw new IOException(HELP);
        }
        return new BitvavoCredentials(apiKey, apiSecret);
    }
}
