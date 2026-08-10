package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BitvavoClient;
import nl.debo.cryptodata.tools.Kline;
import nl.debo.cryptodata.utils.ConsoleColor;
import nl.debo.cryptodata.utils.FileUtil;
import nl.debo.cryptodata.utils.KlineCsvStore;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point: downloads candlestick history for every Bitvavo market/interval
 * combination into {@code output/klines-bitvavo/} (one CSV per combination).
 * The first run backfills from each market's first candle; later runs append
 * only the candles newer than the last saved one. The still-open candle is
 * never written.
 *
 * <p>Bitvavo's {@code limit} keeps the newest candles of the
 * requested range, so history is paged <em>backwards</em>: the {@code end}
 * bound walks from now towards the last saved candle, and the collected pages
 * are appended chronologically in one write. Requests are paced by the
 * {@code BitvavoRateLimiter} built into {@link BitvavoClient}, so no extra
 * concurrency bound is needed here.
 *
 * <p>Run directly from the IDE; the jar's default main class remains
 * {@link CryptoAnalysisBitvavo}.</p>
 */
public final class KlineHistoryImportBitvavo {

    private static final List<String> INTERVALS =
            List.of("1h", "2h", "4h", "6h", "8h", "12h", "1d", "1W", "1M");

    /** Bitvavo's maximum candles per request. */
    private static final int PAGE_LIMIT = 1440;

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_PAUSE = Duration.ofSeconds(10);

    private KlineHistoryImportBitvavo() {
    }

    public static void main(String[] args) throws Exception {
        var client = new BitvavoClient();
        Path appDir = FileUtil.applicationDir();
        List<String> markets = FileUtil.readLinesWithFallback(
                KlineHistoryImportBitvavo.class,
                "symbols-bitvavo",
                appDir.resolve("symbols-bitvavo"),
                Path.of("src/main/resources/nl/debo/cryptodata/symbols-bitvavo"),
                Path.of("symbols-bitvavo")
        );

        List<String> activeMarkets = markets.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();

        Path klinesDir = appDir.resolve("output/klines-bitvavo");

        System.out.println(ConsoleColor.green(
                "Bitvavo kline history import started: " + activeMarkets.size() + " markets x "
                        + INTERVALS.size() + " intervals -> " + klinesDir));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = activeMarkets.stream()
                    .flatMap(market -> INTERVALS.stream().map(interval ->
                            CompletableFuture.runAsync(
                                    () -> importHistory(client, klinesDir, market, interval),
                                    executor)))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        System.out.println(ConsoleColor.green("Bitvavo kline history import finished."));
    }

    /**
     * Imports all missing closed candles for one market/interval, paging
     * backwards from now until the page comes back empty (history exhausted)
     * or short (range exhausted). Errors are reported and never break the run.
     */
    private static void importHistory(BitvavoClient client, Path klinesDir, String market, String interval) {
        try {
            Path csvPath = klinesDir.resolve(market + "_" + interval + ".csv");
            long now = System.currentTimeMillis();

            // The candle after the last saved one has not closed yet: nothing
            // new can exist, so skip the request entirely.
            long lastClose = KlineCsvStore.lastSavedCloseTime(csvPath);
            if (lastClose >= 0 && BitvavoClient.closeTime(lastClose + 1, interval) > now) {
                System.out.println(market + " " + interval + ": up to date, no request needed");
                return;
            }

            long start = KlineCsvStore.lastSavedOpenTime(csvPath) + 1;

            // openTime -> kline; sorts chronologically and drops duplicates.
            var byOpenTime = new TreeMap<Long, Kline>();
            long end = now;
            while (end > start) {
                List<Kline> page = fetchPage(client, market, interval, start, end);
                if (page.isEmpty()) {
                    break;
                }
                page.forEach(k -> byOpenTime.put(k.openTime(), k));
                if (page.size() < PAGE_LIMIT) {
                    break;
                }
                end = page.getFirst().openTime();
            }

            List<Kline> closed = new ArrayList<>(byOpenTime.values()).stream()
                    .filter(k -> k.closeTime() <= now)
                    .toList();

            int imported = KlineCsvStore.appendNewKlines(csvPath, closed);
            System.out.println(market + " " + interval + ": +" + imported + " candles");
        } catch (Exception e) {
            System.err.println(ConsoleColor.orange(
                    "Error importing " + market + " (" + interval + "): " + e.getMessage()));
        }
    }

    /**
     * Fetches one page of klines, retrying transient failures. Client errors
     * other than HTTP 429 (e.g. HTTP 404 for a delisted market) are not
     * retried; 429s are already waited out inside {@code JsonHttp}, so hitting
     * one here means even that gave up.
     */
    private static List<Kline> fetchPage(BitvavoClient client, String market, String interval, long start, long end)
            throws InterruptedException {
        for (int attempt = 1; ; attempt++) {
            try {
                return client.getKlinesAsync(market, interval, PAGE_LIMIT, start, end).join();
            } catch (CompletionException e) {
                String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                if (attempt >= MAX_ATTEMPTS || (message != null && message.contains("HTTP 4") && !message.contains("HTTP 429"))) {
                    throw e;
                }
                System.err.println(ConsoleColor.orange(market + " (" + interval + "): " + message
                        + " - retrying in " + RETRY_PAUSE.toSeconds() + "s"));
                Thread.sleep(RETRY_PAUSE);
            }
        }
    }
}
