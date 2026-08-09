package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.*;
import nl.debo.cryptodata.utils.ConsoleColor;
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
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Exchange-independent analysis pipeline: fetches klines for every
 * symbol/interval combination from a {@link KlineSource}, computes
 * RSI / Stochastic RSI / MACD indicators, looks up recent market news per
 * coin and writes the results as CSV and XLSX reports.
 *
 * <p>Everything exchange-specific comes in through the constructor: the
 * client, the symbols file, the interval names and how to derive the base
 * coin from a symbol.
 */
public final class CryptoAnalysis {

    private static final int RSI_PERIOD = 14;
    private static final int STOCH_RSI_PERIOD = 14;
    private static final int K_PERIOD = 3;
    private static final int D_PERIOD = 3;
    private static final int MACD_FAST_PERIOD = 12;
    private static final int MACD_SLOW_PERIOD = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;
    private static final int MADR_SMA_PERIOD = 50;
    private static final int NORMALIZER_WINDOW = 50;
    private static final int KLINE_LIMIT = 200;

    /** News older than this is not considered "recent" and is left out. */
    private static final Duration NEWS_MAX_AGE = Duration.ofHours(48);

    private final KlineSource client;
    private final String symbolsFileName;
    private final List<String> intervals;
    private final UnaryOperator<String> baseAssetOf;
    private final String outputBaseName;

    /**
     * @param client         exchange to fetch klines from
     * @param symbolsFileName name of the symbols file/resource, one symbol per
     *                        line in the exchange's own format
     * @param intervals      interval names in the exchange's own format
     * @param baseAssetOf    derives the base coin from a symbol (for news lookup),
     *                       e.g. {@code "SOLEUR" -> "SOL"} or {@code "SOL-EUR" -> "SOL"}
     * @param outputBaseName prefix of the report files written to {@code output/}
     */
    public CryptoAnalysis(
            KlineSource client,
            String symbolsFileName,
            List<String> intervals,
            UnaryOperator<String> baseAssetOf,
            String outputBaseName
    ) {
        this.client = client;
        this.symbolsFileName = symbolsFileName;
        this.intervals = intervals;
        this.baseAssetOf = baseAssetOf;
        this.outputBaseName = outputBaseName;
    }

    public void run() throws Exception {
        // Normalization for the MADR and MACD 0..1 stats: swap either for a
        // StochasticNormalizer or ClampNormalizer to compare strategies.
        var normalizer = new ZScoreNormalizer(NORMALIZER_WINDOW);
        var analyzer = new IndicatorAnalyzer(RSI_PERIOD, STOCH_RSI_PERIOD, K_PERIOD, D_PERIOD,
                MACD_FAST_PERIOD, MACD_SLOW_PERIOD, MACD_SIGNAL_PERIOD,
                MADR_SMA_PERIOD, normalizer, normalizer);

        Path appDir = FileUtil.applicationDir();
        List<String> symbols = FileUtil.readLinesWithFallback(
                CryptoAnalysis.class,
                symbolsFileName,
                appDir.resolve(symbolsFileName),
                Path.of("src/main/resources/nl/debo/cryptodata/" + symbolsFileName),
                Path.of(symbolsFileName)
        );

        var dateStr = LocalDate.now().toString();
        var csvPath = appDir.resolve("output/" + outputBaseName + dateStr + ".csv");
        var xlsxPath = appDir.resolve("output/" + outputBaseName + dateStr + ".xlsx");
        CsvUtil.ensureHeader(csvPath);

        List<String> activeSymbols = symbols.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();

        System.out.println(ConsoleColor.green(
                "Crypto analysis started: " + activeSymbols.size() + " symbols x "
                        + intervals.size() + " intervals"));

        // One news lookup per base coin, shared by all interval rows.
        var newsClient = new NewsClient();
        Map<String, String> newsByCoin = newsClient.fetchLatestHeadlines(
                activeSymbols.stream().map(baseAssetOf).collect(Collectors.toSet()),
                NEWS_MAX_AGE);

        List<ResultRow> results = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = activeSymbols.parallelStream()
                    .flatMap(symbol -> intervals.stream().map(interval ->
                            client.getKlinesAsync(symbol, interval, KLINE_LIMIT)
                                    .thenAcceptAsync(klines ->
                                            analyzer.latestRow(symbol, interval, klines)
                                                    .map(row -> row.withNews(
                                                            newsByCoin.getOrDefault(baseAssetOf.apply(symbol), "")))
                                                    .ifPresent(row -> {
                                                        printRow(row);
                                                        results.add(row);
                                                    }), executor)
                                    .exceptionally(e -> {
                                        System.err.println(ConsoleColor.orange(
                                                "Error processing symbol " + symbol + " (" + interval + "): " + e.getMessage()));
                                        return null;
                                    })))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        CsvUtil.appendResultRows(csvPath, results);
        XslxPrinter.write(xlsxPath, dateStr, results);

        System.out.println(ConsoleColor.green(
                "Crypto analysis finished: " + results.size() + " rows -> " + csvPath + " and " + xlsxPath));
    }

    private static void printRow(ResultRow row) {
        System.out.printf(
                "%s | %s | %s | Close: %.2f | RSI: %.2f | StochRSI: %.4f | K: %.4f | D: %.4f | MACD: %.4f | Signal: %.4f | Hist: %.4f | MADR: %.4f | MACDstat: %.4f%s%n",
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
                row.madr(),
                row.macdStat(),
                row.news().isEmpty() ? "" : " | News: " + row.news()
        );
    }
}
