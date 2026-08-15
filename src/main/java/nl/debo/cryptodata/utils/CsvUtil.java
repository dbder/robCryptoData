package nl.debo.cryptodata.utils;

import nl.debo.cryptodata.tools.CryptoAnalysis;
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
 * {@code symbol,interval,begin,time,close,rsi,stochRsi,k,d,macd,macdSignal,macdHistogram,madr,macdStat}
 * CSV produced by {@link CryptoAnalysis}. The column layout is fixed; an
 * indicator that was left out of the run has an empty cell, which reads back
 * as {@link Double#NaN}. Older files may end with a news headline column
 * (possibly containing commas); it is parsed with a field limit and
 * discarded.
 */
public final class CsvUtil {

    public static final String HEADER = "symbol,interval,begin,time,close,rsi,stochRsi,k,d,macd,macdSignal,macdHistogram,madr,macdStat";

    /** Widest historical layout: the current columns plus the old news column. */
    private static final int MAX_FIELD_COUNT = 15;

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
     * Appends the given rows to the CSV file. NaN indicator values (left out
     * of the run) are written as empty cells.
     */
    public static void appendResultRows(Path csvPath, List<ResultRow> results) {
        try (var writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            for (var r : results) {
                var csvLine = String.join(",",
                        r.symbol(),
                        r.interval(),
                        r.begin(),
                        r.time(),
                        String.format(Locale.US, "%.2f", r.close()),
                        cell(r.rsi()),
                        cell(r.stochRsi()),
                        cell(r.k()),
                        cell(r.d()),
                        cell(r.macd()),
                        cell(r.macdSignal()),
                        cell(r.macdHistogram()),
                        cell(r.madr()),
                        cell(r.macdStat())
                );
                writer.write(csvLine);
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println(ConsoleColor.orange("Error writing to CSV: " + e.getMessage()));
        }
    }

    /** Four-decimal cell, or empty for NaN. */
    private static String cell(double value) {
        return Double.isNaN(value) ? "" : String.format(Locale.US, "%.4f", value);
    }

    /**
     * Parses the CSV into {@link ResultRow} values. The first line
     * is treated as a header and skipped; malformed lines are reported and skipped.
     * Empty indicator cells read back as NaN. Older files are accepted:
     * missing MACD numbers default to zero, missing madr/macdStat to the
     * neutral 0.5, and the news column that older layouts end with is
     * discarded.
     */
    public static List<ResultRow> readResultRows(Path csvPath) throws IOException {
        var rows = new ArrayList<ResultRow>();
        var lines = Files.readAllLines(csvPath);

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }

            String[] f = line.split(",", MAX_FIELD_COUNT);
            if (f.length < 8) {
                System.err.println(ConsoleColor.orange("Skipping malformed line " + (i + 1) + ": " + line));
                continue;
            }

            // Newer layouts have the Dutch begin date at index 2 and the
            // ISO time at index 3; every older layout has the numeric close
            // at index 3. The layout is decided by parsing.
            String begin = "";
            if (!isNumeric(f[3])) {
                begin = f[2];
                var rest = new ArrayList<>(java.util.Arrays.asList(f));
                rest.remove(2);
                f = rest.toArray(String[]::new);
            }

            // madr and macdStat, when present, are the numeric fields after
            // macdHistogram. A non-numeric field there is the news column of
            // an older layout; from that point on the rest of the line is
            // news and is discarded.
            double madr = 0.5;
            double macdStat = 0.5;
            if (f.length > 11 && isCell(f[11])) {
                madr = parseCell(f[11]);
                if (f.length > 12 && isCell(f[12])) {
                    macdStat = parseCell(f[12]);
                }
            }

            rows.add(new ResultRow(
                    f[0],
                    f[1],
                    begin,
                    f[2],
                    Double.parseDouble(f[3]),
                    parseCell(f[4]),
                    parseCell(f[5]),
                    parseCell(f[6]),
                    parseCell(f[7]),
                    f.length > 8 ? parseCell(f[8]) : 0.0,
                    f.length > 9 ? parseCell(f[9]) : 0.0,
                    f.length > 10 ? parseCell(f[10]) : 0.0,
                    madr,
                    macdStat
            ));
        }

        return rows;
    }

    /** A number, or NaN for an empty cell (indicator left out of the run). */
    private static double parseCell(String value) {
        return value.isEmpty() ? Double.NaN : Double.parseDouble(value);
    }

    /** True for a numeric or empty cell — anything but the free-text news column of old files. */
    private static boolean isCell(String value) {
        return value.isEmpty() || isNumeric(value);
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
