package nl.debo.cryptodata;

import com.fasterxml.jackson.databind.JsonNode;
import nl.debo.cryptodata.utils.JsonHttp;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class BitvavoClient {

    private static final String BASE_URL =
            "https://api.bitvavo.com/v2";

    private final JsonHttp http = new JsonHttp("Bitvavo API");

    public CompletableFuture<List<Kline>> getKlinesAsync(
            String market,
            String interval,
            int limit
    ) {
        var uri = URI.create(
                BASE_URL
                        + "/" + market + "/candles"
                        + "?interval=" + interval
                        + "&limit=" + limit
        );

        return http.getJson(uri).thenApply(root -> parseKlines(root, interval));
    }

    private static List<Kline> parseKlines(JsonNode root, String interval) {
        long intervalMillis = intervalMillis(interval);

        var result = new ArrayList<Kline>();

        for (JsonNode candle : root) {
            long openTime = candle.get(0).asLong();

            result.add(new Kline(
                    openTime,                       // Open time
                    candle.get(1).asDouble(),       // Open
                    candle.get(2).asDouble(),       // High
                    candle.get(3).asDouble(),       // Low
                    candle.get(4).asDouble(),       // Close
                    candle.get(5).asDouble(),       // Volume
                    openTime + intervalMillis - 1   // Close time (not provided by Bitvavo)
            ));
        }

        // Bitvavo returns newest first; Binance (and the analysis code) expect oldest first.
        Collections.reverse(result);

        return result;
    }

    private static long intervalMillis(String interval) {
        long value = Long.parseLong(interval.substring(0, interval.length() - 1));
        char unit = interval.charAt(interval.length() - 1);

        return switch (unit) {
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            case 'w', 'W' -> value * 7 * 86_400_000L;
            case 'M' -> value * 30 * 86_400_000L;
            default -> throw new IllegalArgumentException("Unknown interval: " + interval);
        };
    }
}
