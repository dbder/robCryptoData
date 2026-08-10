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

    public LocalKlineSource(Path klinesDir) {
        this.klinesDir = klinesDir;
    }

    @Override
    public CompletableFuture<List<Kline>> getKlinesAsync(String symbol, String interval, int limit) {
        Path csvPath = klinesDir.resolve(symbol + "_" + interval + ".csv");
        if (!Files.exists(csvPath)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "no local kline history at " + csvPath + " - run KlineHistoryImportBitvavo first"));
        }
        try {
            List<Kline> klines = KlineCsvStore.readKlines(csvPath);
            if (klines.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "no candles in " + csvPath));
            }
            int from = Math.max(0, klines.size() - (limit - 1));
            var window = new ArrayList<>(klines.subList(from, klines.size()));
            window.add(syntheticOpenCandle(window.getLast(), interval));
            return CompletableFuture.completedFuture(window);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
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
