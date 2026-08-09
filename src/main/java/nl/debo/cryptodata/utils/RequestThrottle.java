package nl.debo.cryptodata.utils;

import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Hook for client-side rate limiting in {@link JsonHttp}. Implementations may
 * block in {@link #beforeRequest()} until capacity is available; requests run
 * on virtual threads, so blocking is cheap.
 */
public interface RequestThrottle {

    /** Called before a request is sent; may block until capacity is available. */
    void beforeRequest();

    /** Called with every response so implementations can sync with server-reported state. */
    void afterResponse(HttpResponse<?> response);

    /** How long to wait before retrying a 429 (rate limited) response. */
    default Duration retryDelay(HttpResponse<?> response) {
        return Duration.ofSeconds(60);
    }

    /** No throttling: requests go out immediately, responses are ignored. */
    RequestThrottle NONE = new RequestThrottle() {
        @Override
        public void beforeRequest() {
        }

        @Override
        public void afterResponse(HttpResponse<?> response) {
        }
    };
}
