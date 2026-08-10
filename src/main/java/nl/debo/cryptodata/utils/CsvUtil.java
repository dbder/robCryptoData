package nl.debo.cryptodata.utils;

import nl.debo.cryptodata.CryptoAnalysisBinance;
import nl.debo.cryptodata.tools.ResultRow;

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
 * {@code symbol,interval,begin,time,close,rsi,stochRsi,k,d,macd,macdSignal,macdHistogram,madr,macdStat,news}
 * CSV produced by {@link CryptoAnalysisBinance}. The news headline is deliberately
 * the last column: it may contain commas, so it is parsed with a field limit
 * instead of quoting.
 */
public final class CsvUtil {

    public static final String HEADER = "symbol,interval,begin,time,close,rsi,stochRsi,k,d,macd,macdSignal,macdHistogram,madr,macdStat,news";

    private static final int FIELD_COUNT = 15;

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
                        "%s,%s,%s,%s,%.2f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%s",
                        r.symbol(),
                        r.interval(),
                        r.begin(),
                        r.time(),
                        r.close(),
                        r.rsi(),
                        r.stochRsi(),
                        r.k(),
                        r.d(),
                        r.macd(),
                        r.macdSignal(),
                        r.macdHistogram(),
                        r.madr(),
                        r.macdStat(),
                        r.news()
                );
                writer.write(csvLine);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println(ConsoleColor.orange("Error writing to CSV: " + e.getMessage()));
        }
    }

    /**
     * Parses the CSV into {@link ResultRow} values. The first line
     * is treated as a header and skipped; malformed lines are reported and skipped.
     * Older files without the MACD / news columns are accepted; missing
     * numbers default to zero and missing news to an empty string.
     */
    public static List<ResultRow> readResultRows(Path csvPath) throws IOException {
        var rows = new ArrayList<ResultRow>();
        var lines = Files.readAllLines(csvPath);

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }

            String[] f = line.split(",", FIELD_COUNT);
            if (f.length < 8) {
                System.err.println(ConsoleColor.orange("Skipping malformed line " + (i + 1) + ": " + line));
                continue;
            }

            // The newest layout has the Dutch begin date at index 2 and the
            // ISO time at index 3; every older layout has the numeric close
            // at index 3. An old line whose news contains a comma also
            // splits into 15 fields, so the layout is decided by parsing.
            String begin = "";
            if (!isNumeric(f[3])) {
                begin = f[2];
                var rest = new ArrayList<>(java.util.Arrays.asList(f));
                rest.remove(2);
                f = rest.toArray(String[]::new);
            } else if (f.length == FIELD_COUNT) {
                // Old layout with a comma inside the news: re-split so the
                // whole headline stays in the last field.
                f = line.split(",", FIELD_COUNT - 1);
            }

            // Three older generations: news directly after macdHistogram,
            // then with madr inserted, then with madr and macdStat. The
            // 12-field case is ambiguous (madr or news?), so parse to decide.
            double madr = 0.5;
            double macdStat = 0.5;
            String news = "";
            if (f.length > 13) {
                madr = Double.parseDouble(f[11]);
                macdStat = Double.parseDouble(f[12]);
                news = f[13];
            } else if (f.length > 12) {
                madr = Double.parseDouble(f[11]);
                news = f[12];
            } else if (f.length > 11) {
                try {
                    madr = Double.parseDouble(f[11]);
                } catch (NumberFormatException e) {
                    news = f[11];
                }
            }

            rows.add(new ResultRow(
                    f[0],
                    f[1],
                    begin,
                    f[2],
                    Double.parseDouble(f[3]),
                    Double.parseDouble(f[4]),
                    Double.parseDouble(f[5]),
                    Double.parseDouble(f[6]),
                    Double.parseDouble(f[7]),
                    f.length > 8 ? Double.parseDouble(f[8]) : 0.0,
                    f.length > 9 ? Double.parseDouble(f[9]) : 0.0,
                    f.length > 10 ? Double.parseDouble(f[10]) : 0.0,
                    madr,
                    macdStat,
                    news
            ));
        }

        return rows;
    }

    private static boolean isNumeric(String value) {
        try {
            Double.parseDouble(value);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
