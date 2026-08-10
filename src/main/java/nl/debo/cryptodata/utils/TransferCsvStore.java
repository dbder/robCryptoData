package nl.debo.cryptodata.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Reads and writes the deposit/withdrawal ledger CSV
 * ({@code timestamp,type,symbol,amount,fee,status}). The Bitvavo history
 * endpoints return at most the newest 500 entries, so each run merges what
 * the API returned into this file; entries that age out of the API window
 * stay on record here.
 */
public final class TransferCsvStore {

    public static final String HEADER = "timestamp,type,symbol,amount,fee,status";

    /** One deposit or withdrawal; {@code type} is {@code deposit} or {@code withdrawal}. */
    public record TransferRow(
            long timestamp,
            String type,
            String symbol,
            double amount,
            double fee,
            String status
    ) {
    }

    private TransferCsvStore() {
    }

    /**
     * Merges {@code incoming} into the ledger and rewrites it sorted oldest
     * first, returning the full merged list. A transfer is identified by
     * timestamp, type, symbol and amount; a re-seen transfer replaces the
     * stored one, so a status change (e.g. a withdrawal completing) is
     * picked up instead of duplicated.
     */
    public static List<TransferRow> merge(Path csvPath, List<TransferRow> incoming)
            throws IOException {
        var byKey = new LinkedHashMap<String, TransferRow>();
        for (TransferRow row : readAll(csvPath)) {
            byKey.put(key(row), row);
        }
        for (TransferRow row : incoming) {
            byKey.put(key(row), row);
        }
        var merged = new ArrayList<>(byKey.values());
        merged.sort(Comparator.comparingLong(TransferRow::timestamp));
        writeAll(csvPath, merged);
        return merged;
    }

    private static String key(TransferRow row) {
        return row.timestamp() + "|" + row.type() + "|" + row.symbol() + "|" + row.amount();
    }

    private static void writeAll(Path csvPath, List<TransferRow> rows) throws IOException {
        if (csvPath.getParent() != null) {
            Files.createDirectories(csvPath.getParent());
        }
        try (var writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            for (TransferRow row : rows) {
                writer.write(row.timestamp() + "," + row.type() + "," + row.symbol()
                        + "," + row.amount() + "," + row.fee() + "," + row.status());
                writer.newLine();
            }
        }
    }

    /**
     * Parses all ledger rows, oldest first; an empty list if the file does
     * not exist. The header line is skipped; malformed lines are reported
     * and skipped.
     */
    public static List<TransferRow> readAll(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            return List.of();
        }
        var lines = Files.readAllLines(csvPath);
        var result = new ArrayList<TransferRow>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty()) {
                continue;
            }
            String[] f = line.split(",");
            if (f.length < 6) {
                System.err.println(ConsoleColor.orange(
                        "Skipping malformed line " + (i + 1) + " in " + csvPath + ": " + line));
                continue;
            }
            try {
                result.add(new TransferRow(
                        Long.parseLong(f[0]),
                        f[1],
                        f[2],
                        Double.parseDouble(f[3]),
                        Double.parseDouble(f[4]),
                        f[5]
                ));
            } catch (NumberFormatException e) {
                System.err.println(ConsoleColor.orange(
                        "Skipping malformed line " + (i + 1) + " in " + csvPath + ": " + line));
            }
        }
        return result;
    }
}
