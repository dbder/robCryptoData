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
 * Reads and writes the trade ledger CSV
 * ({@code timestamp,market,id,side,amount,price,fee,feeCurrency}). The
 * Bitvavo trades endpoint returns at most the newest 500 trades per market,
 * so each run merges what the API returned into this file; trades that age
 * out of the API window stay on record here.
 */
public final class TradeCsvStore {

    public static final String HEADER = "timestamp,market,id,side,amount,price,fee,feeCurrency";

    /** One executed trade; {@code side} is {@code buy} or {@code sell}. */
    public record TradeRow(
            long timestamp,
            String market,
            String id,
            String side,
            double amount,
            double price,
            double fee,
            String feeCurrency
    ) {
    }

    private TradeCsvStore() {
    }

    /**
     * Merges {@code incoming} into the ledger and rewrites it sorted oldest
     * first, returning the full merged list. A trade is identified by its
     * exchange-assigned id, so re-fetched trades never duplicate.
     */
    public static List<TradeRow> merge(Path csvPath, List<TradeRow> incoming) throws IOException {
        var byId = new LinkedHashMap<String, TradeRow>();
        for (TradeRow row : readAll(csvPath)) {
            byId.put(row.id(), row);
        }
        for (TradeRow row : incoming) {
            byId.put(row.id(), row);
        }
        var merged = new ArrayList<>(byId.values());
        merged.sort(Comparator.comparingLong(TradeRow::timestamp));
        writeAll(csvPath, merged);
        return merged;
    }

    private static void writeAll(Path csvPath, List<TradeRow> rows) throws IOException {
        if (csvPath.getParent() != null) {
            Files.createDirectories(csvPath.getParent());
        }
        try (var writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            for (TradeRow row : rows) {
                writer.write(row.timestamp() + "," + row.market() + "," + row.id()
                        + "," + row.side() + "," + row.amount() + "," + row.price()
                        + "," + row.fee() + "," + row.feeCurrency());
                writer.newLine();
            }
        }
    }

    /**
     * Parses all ledger rows, oldest first; an empty list if the file does
     * not exist. Header and blank lines are skipped wherever they appear
     * (hand editing can displace them); malformed lines are reported and
     * skipped.
     */
    public static List<TradeRow> readAll(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            return List.of();
        }
        var lines = Files.readAllLines(csvPath);
        var result = new ArrayList<TradeRow>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.equals(HEADER)) {
                continue;
            }
            String[] f = line.split(",");
            if (f.length < 8) {
                System.err.println(ConsoleColor.orange(
                        "Skipping malformed line " + (i + 1) + " in " + csvPath + ": " + line));
                continue;
            }
            try {
                result.add(new TradeRow(
                        Long.parseLong(f[0]),
                        f[1],
                        f[2],
                        f[3],
                        Double.parseDouble(f[4]),
                        Double.parseDouble(f[5]),
                        Double.parseDouble(f[6]),
                        f[7]
                ));
            } catch (NumberFormatException e) {
                System.err.println(ConsoleColor.orange(
                        "Skipping malformed line " + (i + 1) + " in " + csvPath + ": " + line));
            }
        }
        return result;
    }
}
