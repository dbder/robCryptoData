package nl.debo.cryptodata.tools;

import com.fasterxml.jackson.databind.JsonNode;
import nl.debo.cryptodata.utils.JsonHttp;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public final class BitvavoClient implements KlineSource {

    private static final String BASE_URL =
            "https://api.bitvavo.com/v2";

    private final JsonHttp http = new JsonHttp("Bitvavo API", new BitvavoRateLimiter());

    @Override
    public CompletableFuture<List<Kline>> getKlinesAsync(
            String market,
            String interval,
            int limit
    ) {
        return getKlinesAsync(market, interval, limit, -1, -1);
    }

    /**
     * Fetches klines with open time in {@code [start, end)} (epoch millis).
     * A negative value omits the parameter. Note that {@code limit} keeps the
     * <em>newest</em> candles of the range: paging through history must walk
     * the {@code end} bound backwards.
     */
    public CompletableFuture<List<Kline>> getKlinesAsync(
            String market,
            String interval,
            int limit,
            long start,
            long end
    ) {
        var uri = URI.create(
                BASE_URL
                        + "/" + market + "/candles"
                        + "?interval=" + interval
                        + "&limit=" + limit
                        + (start >= 0 ? "&start=" + start : "")
                        + (end >= 0 ? "&end=" + end : "")
        );

        return http.getJson(uri).thenApply(root -> parseKlines(root, interval));
    }

    /**
     * All market prices in one call: market (e.g. {@code "BTC-EUR"}) to last
     * traded price. Markets without a price yet are omitted.
     */
    public CompletableFuture<Map<String, Double>> getTickerPricesAsync() {
        var uri = URI.create(BASE_URL + "/ticker/price");
        return http.getJson(uri).thenApply(BitvavoClient::parseTickerPrices);
    }

    private static Map<String, Double> parseTickerPrices(JsonNode root) {
        var result = new HashMap<String, Double>();
        for (JsonNode node : root) {
            String market = node.path("market").asText();
            String price = node.path("price").asText("");
            if (!market.isEmpty() && !price.isEmpty()) {
                try {
                    result.put(market, Double.parseDouble(price));
                } catch (NumberFormatException ignored) {
                    // market without a parseable price: skip
                }
            }
        }
        return result;
    }

    private static List<Kline> parseKlines(JsonNode root, String interval) {
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
                    closeTime(openTime, interval)   // Close time (not provided by Bitvavo)
            ));
        }

        // Bitvavo returns newest first; the analysis code expects oldest first.
        Collections.reverse(result);

        return result;
    }

    /**
     * Last millisecond of the candle's period. Months vary in length, so the
     * {@code 1M} close is computed on the calendar (UTC) instead of with a
     * fixed duration — a fixed 30 days would mark a still-open 31-day month
     * as closed.
     */
    public static long closeTime(long openTime, String interval) {
        long value = Long.parseLong(interval.substring(0, interval.length() - 1));
        char unit = interval.charAt(interval.length() - 1);

        if (unit == 'M') {
            return Instant.ofEpochMilli(openTime)
                    .atZone(ZoneOffset.UTC)
                    .plusMonths(value)
                    .toInstant()
                    .toEpochMilli() - 1;
        }

        long intervalMillis = switch (unit) {
            case 'm' -> value * 60_000L;
            case 'h' -> value * 3_600_000L;
            case 'd' -> value * 86_400_000L;
            case 'w', 'W' -> value * 7 * 86_400_000L;
            default -> throw new IllegalArgumentException("Unknown interval: " + interval);
        };
        return openTime + intervalMillis - 1;
    }
}
