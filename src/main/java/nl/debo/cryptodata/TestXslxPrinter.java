package nl.debo.cryptodata;

import nl.debo.cryptodata.utils.CsvUtil;

import java.nio.file.Files;
import java.nio.file.Path;
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

        List<CryptoAnalysis.ResultRow> results = CsvUtil.readResultRows(csvPath);
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
}
