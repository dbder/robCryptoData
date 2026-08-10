package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.ConsoleColor;
import nl.debo.cryptodata.utils.KlineCsvStore;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;

/**
 * Imports missing closed candles into the local candle store, one
 * market/interval combination per {@link #update} call, so callers can
 * refresh exactly what they need. The first update of a combination
 * backfills from the market's first candle; later updates append only the
 * candles newer than the last saved one, and skip the request entirely when
 * nothing new can exist yet. The still-open candle is never written.
 *
 * <p>Bitvavo's {@code limit} keeps the newest candles of the requested
 * range, so history is paged <em>backwards</em>: the {@code end} bound walks
 * from now towards the last saved candle, and the collected pages are
 * appended chronologically in one write. Requests are paced by the
 * {@code BitvavoRateLimiter} built into {@link BitvavoClient}.</p>
 */
public final class KlineHistoryImporter {

    /** Bitvavo's maximum candles per request. */
    private static final int PAGE_LIMIT = 1440;

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration RETRY_PAUSE = Duration.ofSeconds(10);

    private final BitvavoClient client;
    private final Path klinesDir;

    public KlineHistoryImporter(BitvavoClient client, Path klinesDir) {
        this.client = client;
        this.klinesDir = klinesDir;
    }

    /** Path of the candle CSV for one market/interval combination. */
    public Path csvPath(String market, String interval) {
        return klinesDir.resolve(market + "_" + interval + ".csv");
    }

    /**
     * Imports all missing closed candles for one market/interval, paging
     * backwards from now until the page comes back empty (history exhausted)
     * or short (range exhausted).
     *
     * @return the number of candles appended (0 when already up to date)
     */
    public int update(String market, String interval) throws IOException, InterruptedException {
        Path csvPath = csvPath(market, interval);
        long now = System.currentTimeMillis();

        // The candle after the last saved one has not closed yet: nothing
        // new can exist, so skip the request entirely.
        long lastClose = KlineCsvStore.lastSavedCloseTime(csvPath);
        if (lastClose >= 0 && BitvavoClient.closeTime(lastClose + 1, interval) > now) {
            System.out.println(market + " " + interval + ": up to date, no request needed");
            return 0;
        }

        long start = KlineCsvStore.lastSavedOpenTime(csvPath) + 1;

        // openTime -> kline; sorts chronologically and drops duplicates.
        var byOpenTime = new TreeMap<Long, Kline>();
        long end = now;
        while (end > start) {
            List<Kline> page = fetchPage(market, interval, start, end);
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
        return imported;
    }

    /**
     * Fetches one page of klines, retrying transient failures. Client errors
     * other than HTTP 429 (e.g. HTTP 404 for a delisted market) are not
     * retried; 429s are already waited out inside {@code JsonHttp}, so hitting
     * one here means even that gave up.
     */
    private List<Kline> fetchPage(String market, String interval, long start, long end)
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
