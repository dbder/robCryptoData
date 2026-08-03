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

import java.util.concurrent.CompletableFuture;
import java.util.List;

public final class BinanceClient {

    private static final String BASE_URL =
            "https://api.binance.com/api/v3/klines";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public BinanceClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .executor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor())
                .build();

        this.objectMapper = new ObjectMapper();
    }

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

        var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException(
                                "Binance API returned HTTP "
                                        + response.statusCode()
                                        + ": "
                                        + response.body()
                        );
                    }
                    try {
                        return parseKlines(response.body());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }

    private List<Kline> parseKlines(String json) throws IOException {

        JsonNode root = objectMapper.readTree(json);

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