package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.RequestThrottle;

import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Keeps the app inside Bitvavo's rate limit of 1000 weight points per minute
 * per IP (unauthenticated). Exceeding it triggers HTTP 429 and a 15 minute IP
 * block, so this limiter budgets requests client-side and pauses until the
 * window resets rather than ever running the budget dry.
 *
 * <p>Each candle request costs 1 weight point. Two mechanisms work together:
 * request starts are paced {@link #PACE_MILLIS} apart, which by itself keeps
 * the request rate under the limit, and a budget tracker (decremented per
 * request, synced with the {@code bitvavo-ratelimit-remaining} /
 * {@code bitvavo-ratelimit-resetat} response headers, always trusting
 * whichever says less is left) pauses everything until the window resets if
 * the server reports consumption this process did not cause. The server
 * headers are cached at the edge and can lag behind or report a reset time in
 * the past, which is why neither mechanism alone is trusted.
 *
 * <p>See <a href="https://docs.bitvavo.com/docs/rate-limits/">Bitvavo rate limits</a>.
 */
public final class BitvavoRateLimiter implements RequestThrottle {

    /** Stop this many points before the server limit; headers lag, so keep headroom. */
    private static final int SAFETY_MARGIN = 50;

    /**
     * Minimum time between request starts. 65 ms spaces requests to at most
     * ~920 per minute, which stays under the budget even if every request
     * costs one point — so the budget check below is only a backstop for
     * consumption this process did not cause itself.
     */
    private static final long PACE_MILLIS = 65;

    /** Window length used until the server tells us the actual reset time. */
    private static final long WINDOW_MILLIS = 60_000;

    /** Extra sleep past the reset time so we never wake up a moment early. */
    private static final long RESET_SLACK_MILLIS = 250;

    /** Print the remaining budget every this many responses. */
    private static final int FEEDBACK_EVERY = 100;

    private final Object lock = new Object();
    private long limit = 1000;
    private long remaining = 1000;
    private long resetAtMillis = 0;
    private long nextSlotMillis = 0;
    private long responses = 0;
    private long lastPauseLogMillis = 0;

    @Override
    public void beforeRequest() {
        long mySlot;
        synchronized (lock) {
            mySlot = Math.max(System.currentTimeMillis(), nextSlotMillis);
            nextSlotMillis = mySlot + PACE_MILLIS;
        }

        while (true) {
            long now = System.currentTimeMillis();
            if (now < mySlot) {
                sleep(mySlot - now);
                continue;
            }
            synchronized (lock) {
                now = System.currentTimeMillis();
                if (now >= resetAtMillis) {
                    remaining = limit;
                    resetAtMillis = now + WINDOW_MILLIS;
                }
                if (remaining > SAFETY_MARGIN) {
                    remaining--;
                    return;
                }
                // Budget exhausted earlier than pacing predicts (server headers
                // reported extra consumption): reschedule past the reset,
                // re-staggered behind any other rescheduled requests.
                long resume = resetAtMillis + RESET_SLACK_MILLIS;
                mySlot = Math.max(nextSlotMillis, resume);
                nextSlotMillis = mySlot + PACE_MILLIS;
                if (now - lastPauseLogMillis > 5_000) {
                    lastPauseLogMillis = now;
                    System.out.printf(
                            "Bitvavo rate limit budget nearly spent (%d/%d points left), pausing %.1fs until the window resets%n",
                            remaining, limit, (resume - now) / 1000.0);
                }
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for Bitvavo rate limit window", e);
        }
    }

    @Override
    public void afterResponse(HttpResponse<?> response) {
        long serverLimit = headerLong(response, "bitvavo-ratelimit-limit");
        long serverRemaining = headerLong(response, "bitvavo-ratelimit-remaining");
        long serverResetAt = headerLong(response, "bitvavo-ratelimit-resetat");

        long shownRemaining;
        long shownLimit;
        long resetInMillis;
        boolean print;
        synchronized (lock) {
            long now = System.currentTimeMillis();
            if (serverLimit > 0) {
                limit = serverLimit;
            }
            if (serverResetAt > now) {
                resetAtMillis = serverResetAt;
            }
            if (serverRemaining >= 0) {
                remaining = Math.min(remaining, serverRemaining);
            }
            responses++;
            print = responses == 1 || responses % FEEDBACK_EVERY == 0
                    || response.statusCode() == 429;
            shownRemaining = remaining;
            shownLimit = limit;
            resetInMillis = Math.max(0, resetAtMillis - now);
        }

        if (response.statusCode() == 429) {
            System.err.printf(
                    "Bitvavo rate limit exceeded (HTTP 429) — unauthenticated clients are blocked for 15 minutes%n");
        }
        if (print) {
            System.out.printf(
                    "Bitvavo rate limit: %d/%d points remaining (window resets in %.1fs)%n",
                    shownRemaining, shownLimit, resetInMillis / 1000.0);
        }
    }

    @Override
    public Duration retryDelay(HttpResponse<?> response) {
        // On a 429 the resetat header holds the ban expiry ("The ban expires
        // at <resetat>"). Without the header, assume the documented worst
        // case: a 15 minute block for unauthenticated clients.
        long resetAt = headerLong(response, "bitvavo-ratelimit-resetat");
        long waitMillis = resetAt - System.currentTimeMillis();
        if (waitMillis <= 0) {
            waitMillis = Duration.ofMinutes(15).toMillis();
        }
        return Duration.ofMillis(waitMillis + RESET_SLACK_MILLIS);
    }

    /** First value of the header as a long, or {@code -1} if absent or malformed. */
    private static long headerLong(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).map(value -> {
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException e) {
                return -1L;
            }
        }).orElse(-1L);
    }
}
