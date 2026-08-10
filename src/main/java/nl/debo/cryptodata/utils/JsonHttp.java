package nl.debo.cryptodata.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Small async HTTP-to-JSON helper shared by the exchange clients: virtual
 * thread executor, 30 second timeouts, non-200 responses turned into failed
 * futures.
 *
 * <p>An optional {@link RequestThrottle} gates every request and observes
 * every response; HTTP 429 responses are retried a few times after the delay
 * the throttle asks for.
 */
public final class JsonHttp {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final String apiName;
    private final RequestThrottle throttle;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param apiName used in error messages, e.g. {@code "Bitvavo API"}
     */
    public JsonHttp(String apiName) {
        this(apiName, RequestThrottle.NONE);
    }

    public JsonHttp(String apiName, RequestThrottle throttle) {
        this.apiName = apiName;
        this.throttle = throttle;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .executor(executor)
                .build();
        this.objectMapper = new ObjectMapper();
    }

    /**
     * Executes an async GET request and parses the response body as JSON.
     */
    public CompletableFuture<JsonNode> getJson(URI uri) {
        return getJson(uri, Map::of);
    }

    /**
     * Like {@link #getJson(URI)} but with extra request headers, recomputed on
     * every attempt so time-sensitive values (request signatures) stay fresh
     * across rate-limit retries.
     */
    public CompletableFuture<JsonNode> getJson(URI uri, Supplier<Map<String, String>> headers) {
        return CompletableFuture.supplyAsync(() -> fetch(uri, headers), executor);
    }

    private JsonNode fetch(URI uri, Supplier<Map<String, String>> headers) {
        try {
            for (int attempt = 1; ; attempt++) {
                var builder = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(TIMEOUT)
                        .GET();
                headers.get().forEach(builder::header);
                var request = builder.build();

                throttle.beforeRequest();
                HttpResponse<String> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                throttle.afterResponse(response);

                if (response.statusCode() == 429 && attempt < MAX_ATTEMPTS) {
                    Duration delay = throttle.retryDelay(response);
                    System.err.println(ConsoleColor.orange(String.format(
                            "%s rate limited (HTTP 429), retrying in %ds (attempt %d/%d)",
                            apiName, delay.toSeconds(), attempt, MAX_ATTEMPTS)));
                    Thread.sleep(delay);
                    continue;
                }
                if (response.statusCode() != 200) {
                    throw new RuntimeException(
                            apiName + " returned HTTP "
                                    + response.statusCode()
                                    + ": "
                                    + response.body()
                    );
                }
                return objectMapper.readTree(response.body());
            }
        } catch (IOException e) {
            throw new RuntimeException(apiName + " request failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(apiName + " request interrupted", e);
        }
    }
}
