package nl.debo.cryptodata;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Small runnable harness that exercises {@link XslxPrinter} with real data.
 *
 * <p>It reads a CSV produced by {@link CryptoAnalysis} (default
 * {@code data2026-08-03.csv}), turns every line into a
 * {@link CryptoAnalysis.ResultRow} and renders the styled workbook, so the
 * spreadsheet layout can be inspected without hitting the live Binance API.
 *
 * <p>The project has no test framework wired up, so this is a plain
 * {@code main} you can launch directly:
 * <pre>{@code
 *   java -cp target/classes nl.debo.cryptodata.TestXslxPrinter [input.csv]
 * }</pre>
 */
public final class TestXslxPrinter {

    private TestXslxPrinter() {
    }

    public static void main(String[] args) throws Exception {
        var csvPath = Path.of(args.length > 0 ? args[0] : "data2026-08-03.csv");

        if (!Files.exists(csvPath)) {
            System.err.println("CSV not found: " + csvPath.toAbsolutePath());
            return;
        }

        List<CryptoAnalysis.ResultRow> results = readResultRows(csvPath);
        System.out.println("Parsed " + results.size() + " rows from " + csvPath);

        // Derive the report date and output name from the CSV file name, e.g.
        // "data2026-08-03.csv" -> date "2026-08-03", output "data2026-08-03.xlsx".
        String fileName = csvPath.getFileName().toString();
        String base = "TEST" + (fileName.endsWith(".csv")
                ? fileName.substring(0, fileName.length() - 4)
                : fileName);
        String dateStr = base.startsWith("data") ? base.substring(4) : base;

        var xlsxPath = csvPath.resolveSibling(base + ".xlsx");
        XslxPrinter.write(xlsxPath, dateStr, results);
    }

    /**
     * Parses the CSV into {@link CryptoAnalysis.ResultRow} values. The first line
     * is treated as a header and skipped; the expected column order is
     * {@code symbol,interval,time,close,rsi,stochRsi,k,d}.
     */
    static List<CryptoAnalysis.ResultRow> readResultRows(Path csvPath) throws Exception {
        var rows = new ArrayList<CryptoAnalysis.ResultRow>();
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

            rows.add(new CryptoAnalysis.ResultRow(
                    f[0],
                    f[1],
                    f[2],
                    Double.parseDouble(f[3]),
                    Double.parseDouble(f[4]),
                    Double.parseDouble(f[5]),
                    Double.parseDouble(f[6]),
                    Double.parseDouble(f[7])
            ));
        }

        return rows;
    }
}
