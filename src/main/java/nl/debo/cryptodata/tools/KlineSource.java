package nl.debo.cryptodata.tools;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * A source of candlestick data for a symbol/interval pair, e.g. an exchange
 * REST API. Implementations must return klines ordered oldest first.
 */
@FunctionalInterface
public interface KlineSource {

    CompletableFuture<List<Kline>> getKlinesAsync(String symbol, String interval, int limit);
}
