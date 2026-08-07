package nl.debo.cryptodata.tools;
public record Kline(
        long openTime,
        double open,
        double high,
        double low,
        double close,
        double volume,
        long closeTime
) {
}