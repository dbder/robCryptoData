package nl.debo.cryptodata.utils;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Formats timestamps as compact Dutch dates, e.g. {@code "za 9 aug. '26"}.
 * Rendered in UTC so the date matches the exchanges' candle boundaries.
 */
public final class DutchDate {

    private static final DateTimeFormatter COMPACT =
            DateTimeFormatter.ofPattern("EEE d MMM ''yy", Locale.of("nl", "NL"))
                    .withZone(ZoneOffset.UTC);

    private DutchDate() {
    }

    public static String compact(long epochMillis) {
        return COMPACT.format(Instant.ofEpochMilli(epochMillis));
    }
}
