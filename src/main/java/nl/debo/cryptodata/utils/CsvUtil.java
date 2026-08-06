package nl.debo.cryptodata.utils;

import nl.debo.cryptodata.CryptoAnalysis;
import nl.debo.cryptodata.ResultRow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes the
 * {@code symbol,interval,time,close,rsi,stochRsi,k,d,macd,macdSignal,macdHistogram}
 * CSV produced by {@link CryptoAnalysis}.
 */
public final class CsvUtil {

    public static final String HEADER = "symbol,interval,time,close,rsi,stochRsi,k,d,macd,macdSignal,macdHistogram";

    private CsvUtil() {
    }

    /**
     * Creates the CSV with a header row if it does not exist yet.
     */
    public static void ensureHeader(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            Files.writeString(csvPath, HEADER + System.lineSeparator(), StandardOpenOption.CREATE);
        }
    }

    /**
     * Appends the given rows to the CSV file.
     */
    public static void appendResultRows(Path csvPath, List<ResultRow> results) {
        try (var writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (var r : results) {
                var csvLine = String.format(
                        Locale.US,
                        "%s,%s,%s,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f",
                        r.symbol(),
                        r.interval(),
                        r.time(),
                        r.close(),
                        r.rsi(),
                        r.stochRsi(),
                        r.k(),
                        r.d(),
                        r.macd(),
                        r.macdSignal(),
                        r.macdHistogram()
                );
                writer.write(csvLine);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing to CSV: " + e.getMessage());
        }
    }

    /**
     * Parses the CSV into {@link ResultRow} values. The first line
     * is treated as a header and skipped; malformed lines are reported and skipped.
     * Older files without the MACD columns are accepted; the missing values
     * default to zero.
     */
    public static List<ResultRow> readResultRows(Path csvPath) throws IOException {
        var rows = new ArrayList<ResultRow>();
        var lines = Files.readAllLines(csvPath);

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }

            String[] f = line.split(",", -1);
            if (f.length < 8) {
                System.err.println("Skipping malformed line " + (i + 1) + ": " + line);
                continue;
            }

            rows.add(new ResultRow(
                    f[0],
                    f[1],
                    f[2],
                    Double.parseDouble(f[3]),
                    Double.parseDouble(f[4]),
                    Double.parseDouble(f[5]),
                    Double.parseDouble(f[6]),
                    Double.parseDouble(f[7]),
                    f.length > 8 ? Double.parseDouble(f[8]) : 0.0,
                    f.length > 9 ? Double.parseDouble(f[9]) : 0.0,
                    f.length > 10 ? Double.parseDouble(f[10]) : 0.0
            ));
        }

        return rows;
    }
}
