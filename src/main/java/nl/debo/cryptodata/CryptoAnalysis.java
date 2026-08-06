package nl.debo.cryptodata;

import nl.debo.cryptodata.utils.CsvUtil;
import nl.debo.cryptodata.utils.FileUtil;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Entry point: fetches klines for every symbol/interval combination, computes
 * RSI / Stochastic RSI / MACD indicators, looks up recent market news per
 * coin and writes the results as CSV, ODS and XLSX reports.
 */
public final class CryptoAnalysis {

    private static final int RSI_PERIOD = 14;
    private static final int STOCH_RSI_PERIOD = 14;
    private static final int K_PERIOD = 3;
    private static final int D_PERIOD = 3;
    private static final int MACD_FAST_PERIOD = 12;
    private static final int MACD_SLOW_PERIOD = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;
    private static final int KLINE_LIMIT = 200;

    /** News older than this is not considered "recent" and is left out. */
    private static final Duration NEWS_MAX_AGE = Duration.ofHours(48);

    /** Quote assets stripped from a pair symbol to get the base coin. */
    private static final List<String> QUOTE_ASSETS =
            List.of("USDT", "USDC", "BUSD", "EUR", "USD", "BTC", "ETH", "BNB");

    private static final List<String> INTERVALS = List.of("1h", "1d", "1w", "1M");

    private CryptoAnalysis() {
    }

    public static void main(String[] args) throws Exception {
        var client = new BinanceClient();
        var analyzer = new StochRsiAnalyzer(RSI_PERIOD, STOCH_RSI_PERIOD, K_PERIOD, D_PERIOD,
                MACD_FAST_PERIOD, MACD_SLOW_PERIOD, MACD_SIGNAL_PERIOD);

        Path appDir = FileUtil.applicationDir();
        List<String> symbols = FileUtil.readLinesWithFallback(
                CryptoAnalysis.class,
                "symbols",
                appDir.resolve("symbols"),
                Path.of("src/main/resources/nl/debo/cryptodata/symbols"),
                Path.of("symbols")
        );

        var dateStr = LocalDate.now().toString();
        var csvPath = appDir.resolve("data" + dateStr + ".csv");
        var odsPath = appDir.resolve("data" + dateStr + ".ods");
        var xlsxPath = appDir.resolve("data" + dateStr + ".xlsx");
        CsvUtil.ensureHeader(csvPath);

        List<String> activeSymbols = symbols.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();

        // One news lookup per base coin, shared by all interval rows.
        var newsClient = new NewsClient();
        Map<String, String> newsByCoin = newsClient.fetchLatestHeadlines(
                activeSymbols.stream().map(CryptoAnalysis::baseAsset).collect(Collectors.toSet()),
                NEWS_MAX_AGE);

        List<ResultRow> results = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = activeSymbols.parallelStream()
                    .flatMap(symbol -> INTERVALS.stream().map(interval ->
                            client.getKlinesAsync(symbol, interval, KLINE_LIMIT)
                                    .thenAcceptAsync(klines ->
                                            analyzer.latestRow(symbol, interval, klines,
                                                            newsByCoin.getOrDefault(baseAsset(symbol), ""))
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

    /**
     * Strips a known quote asset suffix from a pair symbol, e.g.
     * {@code "SOLEUR" -> "SOL"}. Returns the symbol unchanged if no known
     * quote asset matches.
     */
    private static String baseAsset(String symbol) {
        for (var quote : QUOTE_ASSETS) {
            if (symbol.length() > quote.length() && symbol.endsWith(quote)) {
                return symbol.substring(0, symbol.length() - quote.length());
            }
        }
        return symbol;
    }

    private static void printRow(ResultRow row) {
        System.out.printf(
                "%s | %s | %s | Close: %.2f | RSI: %.2f | StochRSI: %.4f | K: %.4f | D: %.4f | MACD: %.4f | Signal: %.4f | Hist: %.4f%s%n",
                row.symbol(),
                row.interval(),
                row.time(),
                row.close(),
                row.rsi(),
                row.stochRsi(),
                row.k(),
                row.d(),
                row.macd(),
                row.macdSignal(),
                row.macdHistogram(),
                row.news().isEmpty() ? "" : " | News: " + row.news()
        );
    }
}
