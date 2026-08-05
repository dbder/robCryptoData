package nl.debo.cryptodata;

import nl.debo.cryptodata.utils.CsvUtil;
import nl.debo.cryptodata.utils.FileUtil;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point: fetches klines for every symbol/interval combination, computes
 * RSI / Stochastic RSI indicators and writes the results as CSV, ODS and XLSX
 * reports.
 */
public final class CryptoAnalysis {

    private static final int RSI_PERIOD = 14;
    private static final int STOCH_RSI_PERIOD = 14;
    private static final int K_PERIOD = 3;
    private static final int D_PERIOD = 3;
    private static final int KLINE_LIMIT = 200;

    private static final List<String> INTERVALS = List.of("1h", "1d", "1w", "1M");

    private CryptoAnalysis() {
    }

    public static void main(String[] args) throws Exception {
        var client = new BinanceClient();
        var analyzer = new StochRsiAnalyzer(RSI_PERIOD, STOCH_RSI_PERIOD, K_PERIOD, D_PERIOD);

        List<String> symbols = FileUtil.readLinesWithFallback(
                CryptoAnalysis.class,
                "symbols",
                Path.of("src/main/java/nl/debo/cryptodata/symbols"),
                Path.of("symbols")
        );

        var dateStr = LocalDate.now().toString();
        var csvPath = Path.of("data" + dateStr + ".csv");
        var odsPath = Path.of("data" + dateStr + ".ods");
        var xlsxPath = Path.of("data" + dateStr + ".xlsx");
        CsvUtil.ensureHeader(csvPath);

        List<ResultRow> results = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = symbols.parallelStream()
                    .map(String::trim)
                    .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                    .flatMap(symbol -> INTERVALS.stream().map(interval ->
                            client.getKlinesAsync(symbol, interval, KLINE_LIMIT)
                                    .thenAcceptAsync(klines ->
                                            analyzer.latestRow(symbol, interval, klines)
                                                    .ifPresent(row -> {
                                                        printRow(row);
                                                        results.add(row);
                                                    }), executor)
                                    .exceptionally(e -> {
                                        System.err.println("Error processing symbol " + symbol + " (" + interval + "): " + e.getMessage());
                                        return null;
                                    })))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        CsvUtil.appendResultRows(csvPath, results);
        OdsPrinter.write(odsPath, dateStr, results);
        XslxPrinter.write(xlsxPath, dateStr, results);
    }

    private static void printRow(ResultRow row) {
        System.out.printf(
                "%s | %s | %s | Close: %.2f | RSI: %.2f | StochRSI: %.4f | K: %.4f | D: %.4f%n",
                row.symbol(),
                row.interval(),
                row.time(),
                row.close(),
                row.rsi(),
                row.stochRsi(),
                row.k(),
                row.d()
        );
    }
}
