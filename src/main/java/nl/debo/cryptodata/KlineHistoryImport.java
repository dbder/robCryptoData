package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BinanceClient;
import nl.debo.cryptodata.tools.Kline;
import nl.debo.cryptodata.utils.FileUtil;
import nl.debo.cryptodata.utils.KlineCsvStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

/**
 * Entry point: downloads candlestick history for every symbol/interval
 * combination into {@code output/klines/} (one CSV per combination). The
 * first run backfills from each pair's first candle on Binance; later runs
 * append only the candles newer than the last saved one. Gaps are never
 * backfilled retroactively; the still-open candle is never written.
 *
 * <p>Run directly from the IDE; the jar's default main class remains
 * {@link CryptoAnalysis}.</p>
 */
public final class KlineHistoryImport {

    private static final List<String> INTERVALS = List.of("1h", "1d", "1w", "1M");
    private static final int PAGE_LIMIT = 1000;
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_PAUSE = Duration.ofSeconds(10);

    /** Bounds parallel imports so a full backfill stays under Binance rate limits. */
    private static final Semaphore CONCURRENT_IMPORTS = new Semaphore(6);

    private KlineHistoryImport() {
    }

    public static void main(String[] args) throws Exception {
        var client = new BinanceClient();
        Path appDir = FileUtil.applicationDir();
        List<String> symbols = FileUtil.readLinesWithFallback(
                CryptoAnalysis.class,
                "symbols",
                appDir.resolve("symbols"),
                Path.of("src/main/resources/nl/debo/cryptodata/symbols"),
                Path.of("symbols")
        );

        List<String> activeSymbols = symbols.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();

        Path klinesDir = appDir.resolve("output/klines");

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = activeSymbols.stream()
                    .flatMap(symbol -> INTERVALS.stream().map(interval ->
                            CompletableFuture.runAsync(
                                    () -> importHistory(client, klinesDir, symbol, interval),
                                    executor)))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }
    }

    /**
     * Imports all missing closed candles for one symbol/interval, paging
     * forward from the last saved openTime (or the pair's first candle when
     * no file exists yet). Errors are reported and never break the run.
     */
    private static void importHistory(BinanceClient client, Path klinesDir, String symbol, String interval) {
        CONCURRENT_IMPORTS.acquireUninterruptibly();
        try {
            Path csvPath = klinesDir.resolve(symbol + "_" + interval + ".csv");
            long start = KlineCsvStore.lastSavedOpenTime(csvPath) + 1;
            int imported = 0;

            while (true) {
                List<Kline> page = fetchPage(client, symbol, interval, start);
                long now = System.currentTimeMillis();
                List<Kline> closed = page.stream()
                        .filter(k -> k.closeTime() <= now)
                        .toList();

                imported += KlineCsvStore.appendNewKlines(csvPath, closed);
                if (page.size() < PAGE_LIMIT || closed.isEmpty()) {
                    break;
                }
                start = closed.getLast().openTime() + 1;
            }

            System.out.println(symbol + " " + interval + ": +" + imported + " candles");
        } catch (Exception e) {
            System.err.println("Error importing " + symbol + " (" + interval + "): " + e.getMessage());
        } finally {
            CONCURRENT_IMPORTS.release();
        }
    }

    /**
     * Fetches one page of klines, retrying transient failures (e.g. rate
     * limits). Client errors other than HTTP 429 (e.g. HTTP 400 for a symbol
     * Binance does not know) are not retried.
     */
    private static List<Kline> fetchPage(BinanceClient client, String symbol, String interval, long startTime)
            throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.getKlinesAsync(symbol, interval, PAGE_LIMIT, startTime).join();
            } catch (CompletionException e) {
                String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                if (attempt >= MAX_ATTEMPTS || (message != null && message.contains("HTTP 4") && !message.contains("HTTP 429"))) {
                    throw e;
                }
                System.err.println(symbol + " (" + interval + "): " + message
                        + " - retrying in " + RETRY_PAUSE.toSeconds() + "s");
                Thread.sleep(RETRY_PAUSE);
            }
        }
    }
}
