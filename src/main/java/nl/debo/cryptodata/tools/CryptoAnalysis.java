package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.ConsoleColor;
import nl.debo.cryptodata.utils.CsvUtil;
import nl.debo.cryptodata.utils.FileUtil;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exchange-independent analysis pipeline: fetches klines for every
 * symbol/interval combination from a {@link KlineSource}, computes
 * RSI / Stochastic RSI / MACD indicators and writes the results as CSV and
 * XLSX reports.
 *
 * <p>Everything exchange-specific comes in through the constructor: the
 * client, the symbols file and the interval names.
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

    private final KlineSource client;
    private final String symbolsFileName;
    private final List<String> intervals;
    private final String outputBaseName;

    /**
     * @param client         exchange to fetch klines from
     * @param symbolsFileName name of the symbols file/resource, one symbol per
     *                        line in the exchange's own format
     * @param intervals      interval names in the exchange's own format
     * @param outputBaseName prefix of the report files written to {@code output/}
     */
    public CryptoAnalysis(
            KlineSource client,
            String symbolsFileName,
            List<String> intervals,
            String outputBaseName
    ) {
        this.client = client;
        this.symbolsFileName = symbolsFileName;
        this.intervals = intervals;
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

        List<ResultRow> results = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = activeSymbols.parallelStream()
                    .flatMap(symbol -> intervals.stream().map(interval ->
                            client.getKlinesAsync(symbol, interval, KLINE_LIMIT)
                                    .thenAcceptAsync(klines ->
                                            analyzer.latestRow(symbol, interval, klines)
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

        // Report order: symbol alphabetically, the longest time window first
        // within a symbol.
        results.sort(Comparator.comparing(ResultRow::symbol)
                .thenComparing(row -> intervalMillis(row.interval()), Comparator.reverseOrder()));

        CsvUtil.appendResultRows(csvPath, results);
        XslxPrinter.write(xlsxPath, dateStr, results);

        System.out.println(ConsoleColor.green(
                "Crypto analysis finished: " + results.size() + " rows -> " + csvPath + " and " + xlsxPath));
    }

    /**
     * Length of an interval in milliseconds, for ordering only: a month
     * counts as 30 days, exact calendar lengths do not matter here.
     */
    private static long intervalMillis(String interval) {
        long value = Long.parseLong(interval.substring(0, interval.length() - 1));
        return value * switch (interval.charAt(interval.length() - 1)) {
            case 'm' -> 60_000L;
            case 'h' -> 3_600_000L;
            case 'd' -> 86_400_000L;
            case 'w', 'W' -> 7 * 86_400_000L;
            case 'M' -> 30 * 86_400_000L;
            default -> throw new IllegalArgumentException("Unknown interval: " + interval);
        };
    }

    private static void printRow(ResultRow row) {
        System.out.printf(
                "%s | %s | %s | %s | Close: %.2f | RSI: %.2f | StochRSI: %.4f | K: %.4f | D: %.4f | MACD: %.4f | Signal: %.4f | Hist: %.4f | MADR: %.4f | MACDstat: %.4f%n",
                row.symbol(),
                row.interval(),
                row.begin(),
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
                row.macdStat()
        );
    }
}
