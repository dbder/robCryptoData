package nl.debo.cryptodata.tools;

import com.fasterxml.jackson.databind.JsonNode;
import nl.debo.cryptodata.utils.JsonHttp;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Authenticated Bitvavo REST endpoints (balance, deposit and withdrawal
 * history), the private counterpart of {@link BitvavoClient}. Every request
 * is signed with {@link BitvavoAuth}; the URI path and the signed path are
 * derived from the same string so they can never disagree.
 */
public final class BitvavoPrivateClient {

    private static final String BASE_URL = "https://api.bitvavo.com/v2";

    /** Bitvavo's maximum history entries per request. */
    public static final int HISTORY_LIMIT = 500;

    private final BitvavoCredentials credentials;
    private final JsonHttp http = new JsonHttp("Bitvavo API (private)", new BitvavoRateLimiter());

    public BitvavoPrivateClient(BitvavoCredentials credentials) {
        this.credentials = credentials;
    }

    /** One asset's balance; amounts in the asset itself, not EUR. */
    public record AssetBalance(String symbol, double available, double inOrder) {
        public double total() {
            return available + inOrder;
        }
    }

    /** One deposit or withdrawal; {@code amount} and {@code fee} in {@code symbol}. */
    public record Transfer(long timestamp, String symbol, double amount, double fee, String status) {
    }

    /** All assets with a balance. GET /v2/balance. */
    public CompletableFuture<List<AssetBalance>> getBalanceAsync() {
        return getSigned("/balance").thenApply(BitvavoPrivateClient::parseBalances);
    }

    /** Newest-first deposit history, at most {@link #HISTORY_LIMIT} entries. */
    public CompletableFuture<List<Transfer>> getDepositHistoryAsync() {
        return getSigned("/depositHistory?limit=" + HISTORY_LIMIT)
                .thenApply(BitvavoPrivateClient::parseTransfers);
    }

    /** Newest-first withdrawal history, at most {@link #HISTORY_LIMIT} entries. */
    public CompletableFuture<List<Transfer>> getWithdrawalHistoryAsync() {
        return getSigned("/withdrawalHistory?limit=" + HISTORY_LIMIT)
                .thenApply(BitvavoPrivateClient::parseTransfers);
    }

    /**
     * Signed GET; {@code pathWithQuery} excludes the {@code /v2} prefix, which
     * is added both to the URI and to the signed string here.
     */
    private CompletableFuture<JsonNode> getSigned(String pathWithQuery) {
        var uri = URI.create(BASE_URL + pathWithQuery);
        return http.getJson(uri,
                () -> BitvavoAuth.headers(credentials, "GET", "/v2" + pathWithQuery));
    }

    private static List<AssetBalance> parseBalances(JsonNode root) {
        var result = new ArrayList<AssetBalance>();
        for (JsonNode node : root) {
            result.add(new AssetBalance(
                    node.path("symbol").asText(),
                    node.path("available").asDouble(0),
                    node.path("inOrder").asDouble(0)
            ));
        }
        return result;
    }

    private static List<Transfer> parseTransfers(JsonNode root) {
        var result = new ArrayList<Transfer>();
        for (JsonNode node : root) {
            result.add(new Transfer(
                    node.path("timestamp").asLong(0),
                    node.path("symbol").asText(),
                    node.path("amount").asDouble(0),
                    node.path("fee").asDouble(0),
                    // Deposits carry no status field: only settled ones are returned.
                    node.path("status").asText("completed")
            ));
        }
        return result;
    }
}
