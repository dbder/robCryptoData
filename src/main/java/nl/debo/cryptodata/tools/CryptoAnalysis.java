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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exchange-independent analysis pipeline: fetches klines for every
 * symbol/interval combination from a {@link KlineSource}, computes the
 * selected {@link Indicator}s (RSI / Stochastic RSI / K / D / MACD, plus
 * MADR which is always on) and writes the results as CSV and XLSX reports.
 *
 * <p>Everything exchange-specific comes in through the constructor: the
 * client, the symbols file, the interval names and the indicator selection.
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
    private final Set<Indicator> indicators;
    private final String outputBaseName;
    private final LocalDate reportDate;

    /**
     * @param client         exchange to fetch klines from
     * @param symbolsFileName name of the symbols file/resource, one symbol per
     *                        line in the exchange's own format
     * @param intervals      interval names in the exchange's own format
     * @param indicators     the indicators to report on, e.g. from
     *                       {@link Indicator#readSelection}
     * @param outputBaseName prefix of the report files written to {@code output/}
     */
    public CryptoAnalysis(
            KlineSource client,
            String symbolsFileName,
            List<String> intervals,
            Set<Indicator> indicators,
            String outputBaseName
    ) {
        this(client, symbolsFileName, intervals, indicators, outputBaseName, LocalDate.now());
    }

    /**
     * Pipeline whose reports are stamped with {@code reportDate} instead of
     * today. The date only names the output files; running the analysis *as
     * of* that date is the {@link KlineSource}'s job (e.g. a
     * {@link LocalKlineSource} with a cutoff).
     */
    public CryptoAnalysis(
            KlineSource client,
            String symbolsFileName,
            List<String> intervals,
            Set<Indicator> indicators,
            String outputBaseName,
            LocalDate reportDate
    ) {
        this.client = client;
        this.symbolsFileName = symbolsFileName;
        this.intervals = intervals;
        this.indicators = Set.copyOf(indicators);
        this.outputBaseName = outputBaseName;
        this.reportDate = reportDate;
    }

    public void run() throws Exception {
        // Normalization for the MADR and MACD 0..1 stats: swap either for a
        // StochasticNormalizer or ClampNormalizer to compare strategies.
        var normalizer = new ZScoreNormalizer(NORMALIZER_WINDOW);
        var analyzer = new IndicatorAnalyzer(indicators, RSI_PERIOD, STOCH_RSI_PERIOD, K_PERIOD, D_PERIOD,
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

        var dateStr = reportDate.toString();
        var csvPath = appDir.resolve("output/" + outputBaseName + dateStr + ".csv");
        var xlsxPath = appDir.resolve("output/" + outputBaseName + dateStr + ".xlsx");
        CsvUtil.ensureHeader(csvPath);

        List<String> activeSymbols = symbols.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();

        System.out.println(ConsoleColor.green(
                "Crypto analysis started: " + activeSymbols.size() + " symbols x "
                        + intervals.size() + " intervals, indicators: " + Indicator.describe(indicators)));

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
        XslxPrinter.write(xlsxPath, dateStr, results, indicators);

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

    /** One console line per row; indicators left out of the run are not printed. */
    private static void printRow(ResultRow row) {
        var line = new StringBuilder()
                .append(row.symbol()).append(" | ")
                .append(row.interval()).append(" | ")
                .append(row.begin()).append(" | ")
                .append(row.time())
                .append(String.format(" | Close: %.2f", row.close()));
        if (row.has(Indicator.RSI)) {
            line.append(String.format(" | RSI: %.2f", row.rsi()));
        }
        if (row.has(Indicator.STOCH_RSI)) {
            line.append(String.format(" | StochRSI: %.4f", row.stochRsi()));
        }
        if (row.has(Indicator.K)) {
            line.append(String.format(" | K: %.4f", row.k()));
        }
        if (row.has(Indicator.D)) {
            line.append(String.format(" | D: %.4f", row.d()));
        }
        if (row.has(Indicator.MACD)) {
            line.append(String.format(" | MACD: %.4f | Signal: %.4f | Hist: %.4f",
                    row.macd(), row.macdSignal(), row.macdHistogram()));
        }
        line.append(String.format(" | MADR: %.4f", row.madr()));
        if (row.has(Indicator.MACD)) {
            line.append(String.format(" | MACDstat: %.4f", row.macdStat()));
        }
        System.out.println(line);
    }
}
