package nl.debo.cryptodata;
public record IndicatorValue(
        Kline kline,
        double rsi,
        double stochasticRsi,
        double k,
        double d
) {
}