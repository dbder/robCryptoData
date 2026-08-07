package nl.debo.cryptodata.tools;

/**
 * One line of the report: the latest complete indicator values for a
 * symbol/interval combination.
 *
 * <p>Besides the raw values it derives coarse "range" labels per indicator so
 * the spreadsheet filter dropdowns offer a handful of buckets instead of one
 * entry per distinct decimal value.
 */
public record ResultRow(
        String symbol,
        String interval,
        String time,
        double close,
        double rsi,
        double stochRsi,
        double k,
        double d,
        double macd,
        double macdSignal,
        double macdHistogram,
        String news
) {

    /** RSI bucketed in steps of 10, e.g. {@code "60-70"}. */
    public String rsiRange() {
        int idx = clampIndex(rsi / 10, 10);
        return String.format("%02d-%d", idx * 10, (idx + 1) * 10);
    }

    /** StochRSI bucketed in steps of 0.2, e.g. {@code "0.2-0.4"}. */
    public String stochRsiRange() {
        return unitRange(stochRsi);
    }

    /** %K bucketed in steps of 0.2, e.g. {@code "0.2-0.4"}. */
    public String kRange() {
        return unitRange(k);
    }

    /** %D bucketed in steps of 0.2, e.g. {@code "0.2-0.4"}. */
    public String dRange() {
        return unitRange(d);
    }

    /** MACD relative to the zero line: {@code Positive}, {@code Negative} or {@code Zero}. */
    public String macdRange() {
        return signRange(macd);
    }

    /** Signal line relative to the zero line: {@code Positive}, {@code Negative} or {@code Zero}. */
    public String macdSignalRange() {
        return signRange(macdSignal);
    }

    /** Histogram relative to the zero line: {@code Positive}, {@code Negative} or {@code Zero}. */
    public String macdHistogramRange() {
        return signRange(macdHistogram);
    }

    /** Buckets a 0..1 scaled value in steps of 0.2. */
    private static String unitRange(double value) {
        int idx = clampIndex(value * 5, 5);
        return String.format(java.util.Locale.US, "%.1f-%.1f", idx * 0.2, (idx + 1) * 0.2);
    }

    private static String signRange(double value) {
        if (value > 0) {
            return "Positive";
        }
        if (value < 0) {
            return "Negative";
        }
        return "Zero";
    }

    /** Floors to a bucket index, clamped to {@code [0, bucketCount - 1]}. */
    private static int clampIndex(double scaled, int bucketCount) {
        return Math.max(0, Math.min(bucketCount - 1, (int) Math.floor(scaled)));
    }
}
