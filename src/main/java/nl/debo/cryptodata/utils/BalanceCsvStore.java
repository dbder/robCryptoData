package nl.debo.cryptodata.utils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads and writes the balance snapshot CSV
 * ({@code timestamp,asset,available,inOrder,priceEur,valueEur}). Every run
 * appends one row per held asset, all sharing the run's timestamp, building
 * up the portfolio history over time. Doubles are written with
 * {@link Double#toString(double)} precision so values round-trip losslessly.
 */
public final class BalanceCsvStore {

    public static final String HEADER = "timestamp,asset,available,inOrder,priceEur,valueEur";

    /** One asset's balance at one moment; {@code priceEur < 0} means unpriced. */
    public record SnapshotRow(
            long timestamp,
            String asset,
            double available,
            double inOrder,
            double priceEur,
            double valueEur
    ) {
    }

    private BalanceCsvStore() {
    }

    /**
     * Appends one snapshot's rows, creating the file (and parent directories)
     * with a header row if needed.
     */
    public static void append(Path csvPath, List<SnapshotRow> rows) throws IOException {
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
            for (var row : rows) {
                writer.write(row.timestamp() + "," + row.asset()
                        + "," + row.available() + "," + row.inOrder()
                        + "," + row.priceEur() + "," + row.valueEur());
                writer.newLine();
            }
        }
    }

    /**
     * Parses all snapshot rows back, in file (chronological) order; an empty
     * list if the file does not exist. The header line is skipped; malformed
     * lines are reported and skipped.
     */
    public static List<SnapshotRow> readAll(Path csvPath) throws IOException {
        if (!Files.exists(csvPath)) {
            return List.of();
        }
        var lines = Files.readAllLines(csvPath);
        var result = new ArrayList<SnapshotRow>();
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
                result.add(new SnapshotRow(
                        Long.parseLong(f[0]),
                        f[1],
                        Double.parseDouble(f[2]),
                        Double.parseDouble(f[3]),
                        Double.parseDouble(f[4]),
                        Double.parseDouble(f[5])
                ));
            } catch (NumberFormatException e) {
                System.err.println(ConsoleColor.orange(
                        "Skipping malformed line " + (i + 1) + " in " + csvPath + ": " + line));
            }
        }
        return result;
    }
}
