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
 * <p>The store holds only closed candles, while the API usually ends the
 * series with the currently open candle. The pipeline filters uncompleted
 * candles out by close time ({@link IndicatorAnalyzer#latestRow}), so this
 * source simply returns the newest {@code limit - 1} closed candles — the
 * same closed-candle window an API request for {@code limit} candles yields
 * once its open candle is filtered away.
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
        // An empty store (e.g. a freshly listed market with no closed candles
        // yet) yields an empty list, which the pipeline reports as "not
        // enough data", not an error.
        List<Kline> klines = KlineCsvStore.readKlines(csvPath);
        int from = Math.max(0, klines.size() - (limit - 1));
        return new ArrayList<>(klines.subList(from, klines.size()));
    }
}