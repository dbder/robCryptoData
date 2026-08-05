package nl.debo.cryptodata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;

import java.util.concurrent.CompletableFuture;
import java.util.List;

public final class BitvavoClient {

    private static final String BASE_URL =
            "https://api.bitvavo.com/v2";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BitvavoClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();

        this.objectMapper = new ObjectMapper();
    }

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

        var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException(
                                "Bitvavo API returned HTTP "
                                        + response.statusCode()
                                        + ": "
                                        + response.body()
                        );
                    }
                    try {
                        return parseKlines(response.body(), interval);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private List<Kline> parseKlines(String json, String interval) throws IOException {

        JsonNode root = objectMapper.readTree(json);

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