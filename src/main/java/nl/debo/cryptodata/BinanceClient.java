package nl.debo.cryptodata;

import com.fasterxml.jackson.databind.JsonNode;
import nl.debo.cryptodata.utils.JsonHttp;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BinanceClient {

    private static final String BASE_URL =
            "https://api.binance.com/api/v3/klines";

    private final JsonHttp http = new JsonHttp("Binance API");

    public CompletableFuture<List<Kline>> getKlinesAsync(
            String symbol,
            String interval,
            int limit
    ) {
        var uri = URI.create(
                BASE_URL
                        + "?symbol=" + symbol
                        + "&interval=" + interval
                        + "&limit=" + limit
        );

        return http.getJson(uri).thenApply(BinanceClient::parseKlines);
    }

    private static List<Kline> parseKlines(JsonNode root) {
        var result = new ArrayList<Kline>();

        for (JsonNode kline : root) {
            result.add(new Kline(
                    kline.get(0).asLong(),    // Open time
                    kline.get(1).asDouble(),  // Open
                    kline.get(2).asDouble(),  // High
                    kline.get(3).asDouble(),  // Low
                    kline.get(4).asDouble(),  // Close
                    kline.get(5).asDouble(),  // Volume
                    kline.get(6).asLong()     // Close time
            ));
        }

        return result;
    }
}
