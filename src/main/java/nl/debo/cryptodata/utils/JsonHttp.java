package nl.debo.cryptodata.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Small async HTTP-to-JSON helper shared by the exchange clients: virtual
 * thread executor, 30 second timeouts, non-200 responses turned into failed
 * futures.
 */
public final class JsonHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiName;

    /**
     * @param apiName used in error messages, e.g. {@code "Binance API"}
     */
    public JsonHttp(String apiName) {
        this.apiName = apiName;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Executes an async GET request and parses the response body as JSON.
     */
    public CompletableFuture<JsonNode> getJson(URI uri) {
        var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException(
                                apiName + " returned HTTP "
                                        + response.statusCode()
                                        + ": "
                                        + response.body()
                        );
                    }
                    try {
                        return objectMapper.readTree(response.body());
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
    }
}
