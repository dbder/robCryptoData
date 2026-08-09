package nl.debo.cryptodata.utils;

import nl.debo.cryptodata.tools.Kline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the per-symbol/interval candlestick CSV files
 * ({@code openTime,open,high,low,close,volume,closeTime}). Candles are
 * appended in chronological order; duplicates are avoided by only writing
 * candles newer than the last saved openTime. Doubles are written with
 * {@link Double#toString(double)} precision so values round-trip losslessly.
 */
public final class KlineCsvStore {

    public static final String HEADER = "openTime,open,high,low,close,volume,closeTime";

    private KlineCsvStore() {
    }

    /**
     * Appends the candles that are newer than the last one already saved in
     * {@code csvPath}, creating the file (and parent directories) with a
     * header row if needed.
     *
     * @return the number of candles written
     */
    public static int appendNewKlines(Path csvPath, List<Kline> klines) throws IOException {
        long last = lastSavedOpenTime(csvPath);
        List<Kline> fresh = klines.stream()
                .filter(k -> k.openTime() > last)
                .toList();
        if (fresh.isEmpty()) {
            return 0;
        }

        if (csvPath.getParent() != null) {
            Files.createDirectories(csvPath.getParent());
        }
        boolean needsHeader = !Files.exists(csvPath) || Files.size(csvPath) == 0;
        try (var writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            if (needsHeader) {
                writer.write(HEADER);
                writer.newLine();
            }
            for (var k : fresh) {
                writer.write(toCsvLine(k));
                writer.newLine();
            }
        }
        return fresh.size();
    }

    /**
     * The openTime of the last candle saved in {@code csvPath}, or {@code -1}
     * if the file does not exist or holds no parseable data line. Candles are
     * appended chronologically, so the last line holds the maximum openTime.
     * Malformed lines (e.g. a partial write from an aborted run) are reported
     * and skipped.
     */
    public static long lastSavedOpenTime(Path csvPath) {
        if (!Files.exists(csvPath)) {
            return -1;
        }
        try {
            List<String> lines = Files.readAllLines(csvPath);
            for (int i = lines.size() - 1; i >= 1; i--) {
                String line = lines.get(i).strip();
                if (line.isEmpty()) {
                    continue;
                }
                int comma = line.indexOf(',');
                try {
                    return Long.parseLong(comma < 0 ? line : line.substring(0, comma));
                } catch (NumberFormatException e) {
                    System.err.println(ConsoleColor.orange(
                            "Skipping malformed line " + (i + 1) + " in " + csvPath + ": " + line));
                }
            }
            return -1;
        } catch (IOException e) {
            System.err.println(ConsoleColor.orange("Error reading " + csvPath + ": " + e.getMessage()));
            return -1;
        }
    }

    /**
     * Parses a candlestick CSV written by this store back into klines, in
     * file (chronological) order. The header line is skipped; malformed
     * lines are reported and skipped.
     */
    public static List<Kline> readKlines(Path csvPath) throws IOException {
        var lines = Files.readAllLines(csvPath);
        var result = new ArrayList<Kline>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",");
            if (f.length < 7) {
                System.err.println(ConsoleColor.orange(
                        "Skipping malformed line " + (i + 1) + " in " + csvPath + ": " + line));
                continue;
            }
            try {
                result.add(new Kline(
                        Long.parseLong(f[0]),
                        Double.parseDouble(f[1]),
                        Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]),
                        Double.parseDouble(f[4]),
                        Double.parseDouble(f[5]),
                        Long.parseLong(f[6])
                ));
            } catch (NumberFormatException e) {
                System.err.println(ConsoleColor.orange(
                        "Skipping malformed line " + (i + 1) + " in " + csvPath + ": " + line));
            }
        }
        return result;
    }

    private static String toCsvLine(Kline k) {
        return k.openTime() + "," + k.open() + "," + k.high() + "," + k.low()
                + "," + k.close() + "," + k.volume() + "," + k.closeTime();
    }
}
