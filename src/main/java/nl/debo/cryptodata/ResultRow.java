package nl.debo.cryptodata;

/**
 * One line of the report: the latest complete indicator values for a
 * symbol/interval combination.
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
        double macdHistogram
) {
}
