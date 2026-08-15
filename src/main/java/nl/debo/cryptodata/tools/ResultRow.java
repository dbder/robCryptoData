package nl.debo.cryptodata.tools;

/**
 * One line of the report: the latest complete indicator values for a
 * symbol/interval combination.
 *
 * <p>An indicator that was not selected for the run (see {@link Indicator})
 * is stored as {@link Double#NaN}: the row simply has no value for it, and
 * {@link #has(Indicator)} says so. Consumers skip such values; the
 * {@link #score()} averages only what is present.</p>
 *
 * <p>Besides the raw values it derives coarse "range" labels per indicator so
 * the spreadsheet filter dropdowns offer a handful of buckets instead of one
 * entry per distinct decimal value.
 */
public record ResultRow(
        String symbol,
        String interval,
        String begin,
        String time,
        double close,
        double rsi,
        double stochRsi,
        double k,
        double d,
        double macd,
        double macdSignal,
        double macdHistogram,
        double madr,
        double macdStat
) {

    /**
     * Whether this row carries a value for {@code indicator}; false when the
     * indicator was left out of the run. {@link Indicator#MACD} counts as
     * present when the MACD line is.
     */
    public boolean has(Indicator indicator) {
        return !Double.isNaN(switch (indicator) {
            case RSI -> rsi;
            case STOCH_RSI -> stochRsi;
            case K -> k;
            case D -> d;
            case MACD -> macd;
            case MADR -> madr;
        });
    }

    /**
     * Reporting currency of the pair: {@code "EUR"} for EUR-quoted pairs,
     * {@code "USD"} for pairs quoted in dollars or dollar stablecoins
     * (USD, USDT, USDC, BUSD), otherwise the raw quote asset (e.g. {@code "BTC"}).
     */
    public String quoteCurrency() {
        String quote = PairSymbols.quote(symbol);
        return switch (quote) {
            case "USDT", "USDC", "BUSD", "USD" -> "USD";
            default -> quote;
        };
    }

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

    /** MADR bucketed in steps of 0.2, e.g. {@code "0.2-0.4"} (0 = buy, 1 = sell). */
    public String madrRange() {
        return unitRange(madr);
    }

    /**
     * Normalized MACD momentum bucketed in steps of 0.2 (0 = bullish
     * momentum / buy, 1 = bearish momentum / sell, 0.5 = crossover).
     */
    public String macdStatRange() {
        return unitRange(macdStat);
    }

    /**
     * Combined 0..1 buy/sell score: the average of every 0..1-scaled
     * statistic present in the row (RSI/100, StochRSI, K, D, MADR, MACD
     * stat), all oriented the same way. 0 = every indicator says buy,
     * 1 = every indicator says sell, 0.5 = neutral or mixed. Only selected
     * indicators take part (the others are NaN), so a run on RSI and MADR
     * scores the average of RSI/100 and MADR; with nothing scorable selected
     * the score is the neutral 0.5.
     */
    public double score() {
        double sum = 0;
        int count = 0;
        for (double stat : new double[] {rsi / 100.0, stochRsi, k, d, madr, macdStat}) {
            if (!Double.isNaN(stat)) {
                sum += stat;
                count++;
            }
        }
        return count == 0 ? 0.5 : sum / count;
    }

    /** Score bucketed in steps of 0.2, e.g. {@code "0.2-0.4"} (0 = buy, 1 = sell). */
    public String scoreRange() {
        return unitRange(score());
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
