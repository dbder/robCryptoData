package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.KlineCsvStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * {@link KlineSource} that serves candles from the CSVs saved by the kline
 * history import instead of the exchange API.
 *
 * <p>When constructed with a {@link KlineHistoryImporter} the source keeps
 * the store fresh by itself: each request first runs an import for that
 * market/interval, which creates the CSV on first use and appends any candles
 * that closed since the last one saved. The importer skips the exchange
 * request entirely when nothing new can exist yet, so up-to-date requests
 * stay local. Without an importer the source is read-only and fails when the
 * CSV is missing.
 *
 * <p>The store holds only closed candles, while the API ends the series with
 * the currently open candle — which the analysis pipeline assumes and drops.
 * To keep both paths identical this source returns the newest
 * {@code limit - 1} closed candles plus one synthetic open candle at the end
 * (flat at the last close, zero volume), so dropping the last candle leaves
 * exactly the closed-candle window the API path would use.
 *
 * <p>One deliberate difference remains: on an illiquid market the API omits
 * the open candle while it has no trades yet, so the pipeline drops the
 * newest <em>closed</em> candle there instead. The synthetic candle is always
 * present, so this source never loses that candle — arguably more correct,
 * but a source of small differences against the API-based run.
 */
public final class LocalKlineSource implements KlineSource {

    private final Path klinesDir;
    private final KlineHistoryImporter importer;

    /** Read-only source: serves the CSVs as they are, fails when one is missing. */
    public LocalKlineSource(Path klinesDir) {
        this(klinesDir, null);
    }

    /**
     * Self-updating source: brings the CSV up to date through {@code importer}
     * (creating it on first use) before serving each request.
     */
    public LocalKlineSource(Path klinesDir, KlineHistoryImporter importer) {
        this.klinesDir = klinesDir;
        this.importer = importer;
    }

    @Override
    public CompletableFuture<List<Kline>> getKlinesAsync(String symbol, String interval, int limit) {
        // The update inside load() blocks on network and rate-limit pauses,
        // so run it on its own virtual thread instead of the caller's.
        var future = new CompletableFuture<List<Kline>>();
        Thread.ofVirtual().start(() -> {
            try {
                future.complete(load(symbol, interval, limit));
            } catch (Throwable e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    private List<Kline> load(String symbol, String interval, int limit) throws Exception {
        Path csvPath = klinesDir.resolve(symbol + "_" + interval + ".csv");
        if (importer != null) {
            importer.update(symbol, interval);
        }
        if (!Files.exists(csvPath)) {
            throw new IllegalStateException("no local kline history at " + csvPath
                    + " - run KlineHistoryImportBitvavo first or construct this source with a KlineHistoryImporter");
        }
        List<Kline> klines = KlineCsvStore.readKlines(csvPath);
        if (klines.isEmpty()) {
            // No closed candles yet (e.g. a freshly listed market): behave
            // like the API on a market without trades and return no candles,
            // which the pipeline reports as "not enough data", not an error.
            return List.of();
        }
        int from = Math.max(0, klines.size() - (limit - 1));
        var window = new ArrayList<>(klines.subList(from, klines.size()));
        window.add(syntheticOpenCandle(window.getLast(), interval));
        return window;
    }

    /**
     * The candle the exchange would report as currently open. It only exists
     * to be dropped by the pipeline, but is kept plausible (flat at the last
     * close, zero volume) for any other use.
     */
    private static Kline syntheticOpenCandle(Kline lastClosed, String interval) {
        long openTime = lastClosed.closeTime() + 1;
        return new Kline(openTime, lastClosed.close(), lastClosed.close(), lastClosed.close(),
                lastClosed.close(), 0, BitvavoClient.closeTime(openTime, interval));
    }
}