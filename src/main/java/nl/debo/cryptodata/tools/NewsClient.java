package nl.debo.cryptodata.tools;

import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

/**
 * Fetches recent market news headlines per coin from the Google News RSS
 * search feed. No API key is required.
 *
 * <p>News is treated as optional decoration: any failure (network, parse,
 * rate limit) results in an empty headline instead of a failed future.
 */
public final class NewsClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final String BASE_URL = "https://news.google.com/rss/search";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

    /**
     * Fetches the most recent headline for every coin in parallel and blocks
     * until all lookups finish. Coins without recent news map to an empty
     * string.
     */
    public Map<String, String> fetchLatestHeadlines(Collection<String> coins, Duration maxAge) {
        var futures = new HashMap<String, CompletableFuture<String>>();
        for (var coin : coins) {
            futures.put(coin, getLatestHeadlineAsync(coin, maxAge));
        }

        var result = new HashMap<String, String>();
        futures.forEach((coin, future) -> result.put(coin, future.join()));
        return result;
    }

    /**
     * Returns the most recent headline about the given coin published within
     * {@code maxAge}, or an empty string if there is none (or the lookup
     * fails).
     */
    public CompletableFuture<String> getLatestHeadlineAsync(String coin, Duration maxAge) {
        long days = Math.max(1, (maxAge.toHours() + 23) / 24);
        var query = URLEncoder.encode(
                "\"" + coin + "\" crypto when:" + days + "d",
                StandardCharsets.UTF_8);
        var uri = URI.create(BASE_URL + "?q=" + query + "&hl=en-US&gl=US&ceid=US:en");

        var request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(TIMEOUT)
                .header("User-Agent", "Mozilla/5.0")
                .GET()
                .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> {
                    if (response.statusCode() != 200) {
                        throw new RuntimeException("Google News returned HTTP " + response.statusCode());
                    }
                    return latestHeadline(response.body(), Instant.now().minus(maxAge));
                })
                .exceptionally(e -> {
                    System.err.println("News lookup failed for " + coin + ": " + e.getMessage());
                    return "";
                });
    }

    private static String latestHeadline(byte[] rssBytes, Instant oldestAccepted) {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            // The feed is untrusted input: disallow DTDs / external entities.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            Document doc = factory.newDocumentBuilder().parse(new ByteArrayInputStream(rssBytes));

            var items = doc.getElementsByTagName("item");

            // The feed is ordered by relevance, so the first item that is
            // recent enough is the best pick.
            for (int i = 0; i < items.getLength(); i++) {
                var item = items.item(i);

                String title = null;
                String pubDate = null;

                var children = item.getChildNodes();
                for (int j = 0; j < children.getLength(); j++) {
                    var child = children.item(j);
                    switch (child.getNodeName()) {
                        case "title" -> title = child.getTextContent();
                        case "pubDate" -> pubDate = child.getTextContent();
                    }
                }

                if (title == null || title.isBlank() || pubDate == null) {
                    continue;
                }

                Instant published;
                try {
                    published = ZonedDateTime
                            .parse(pubDate.strip(), DateTimeFormatter.RFC_1123_DATE_TIME)
                            .toInstant();
                } catch (Exception e) {
                    continue;
                }

                if (published.isAfter(oldestAccepted)) {
                    return sanitize(title);
                }
            }

            return "";
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse news feed: " + e.getMessage(), e);
        }
    }

    /**
     * Headlines end up in single-line CSV records and console output, so
     * collapse all whitespace (including newlines) to single spaces.
     */
    private static String sanitize(String title) {
        return title.replaceAll("\\s+", " ").strip();
    }
}
