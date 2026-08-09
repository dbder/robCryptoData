package nl.debo.cryptodata.tools;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Builds the authentication headers for private Bitvavo REST endpoints: an
 * HMAC-SHA256 signature over {@code timestamp + method + path}, valid for
 * {@code bitvavo-access-window} milliseconds. Headers must be built freshly
 * per request attempt, because the signed timestamp expires.
 */
public final class BitvavoAuth {

    private static final long ACCESS_WINDOW_MILLIS = 10_000;

    private BitvavoAuth() {
    }

    /**
     * The four authentication headers for one request, timestamped now.
     *
     * @param pathWithQuery the signed path including the {@code /v2} prefix
     *                      and any query string, e.g. {@code "/v2/balance"}
     */
    public static Map<String, String> headers(
            BitvavoCredentials credentials,
            String method,
            String pathWithQuery
    ) {
        long timestamp = System.currentTimeMillis();
        String signature = hmacSha256Hex(
                credentials.apiSecret(),
                timestamp + method + pathWithQuery
        );
        return Map.of(
                "bitvavo-access-key", credentials.apiKey(),
                "bitvavo-access-signature", signature,
                "bitvavo-access-timestamp", Long.toString(timestamp),
                "bitvavo-access-window", Long.toString(ACCESS_WINDOW_MILLIS)
        );
    }

    /** Lowercase hex HMAC-SHA256 of {@code message}, keyed by {@code secret}. */
    private static String hmacSha256Hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
