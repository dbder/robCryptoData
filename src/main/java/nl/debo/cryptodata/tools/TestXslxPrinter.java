package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.CsvUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * Small runnable harness that exercises {@link XslxPrinter} with real data.
 *
 * <p>It reads a CSV produced by {@link CryptoAnalysis} (default
 * {@code output/data-bitvavo<today>.csv}), turns every line into a
 * {@link ResultRow} and renders the styled workbook, so the
 * spreadsheet layout can be inspected without hitting the live exchange API.
 *
 * <p>The project has no test framework wired up, so this is a plain
 * {@code main} you can launch directly:
 * <pre>{@code
 *   java -cp target/classes nl.debo.cryptodata.tools.TestXslxPrinter [input.csv]
 * }</pre>
 */
public final class TestXslxPrinter {

    private TestXslxPrinter() {
    }

    public static void main(String[] args) throws Exception {
        var csvPath = Path.of(args.length > 0
                ? args[0]
                : "output/data-bitvavo" + LocalDate.now() + ".csv");

        if (!Files.exists(csvPath)) {
            System.err.println("CSV not found: " + csvPath.toAbsolutePath());
            return;
        }

        List<ResultRow> results = CsvUtil.readResultRows(csvPath);
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
