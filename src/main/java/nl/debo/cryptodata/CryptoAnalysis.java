package nl.debo.cryptodata;
import java.util.List;
import java.util.stream.IntStream;

public final class CryptoAnalysis {

    private static final int RSI_PERIOD = 14;
    private static final int STOCH_RSI_PERIOD = 14;
    private static final int K_PERIOD = 3;
    private static final int D_PERIOD = 3;

    public static void main(String[] args)
            throws Exception {

        var client = new BinanceClient();

        var klines = client.getKlines(
                "BTCUSDT",
                "1h",
                200
        );

        // Remove the currently open candle.
        var closedKlines = klines.subList(
                0,
                klines.size() - 1
        );

        var closes = closedKlines.stream()
                .map(Kline::close)
                .toList();

        var rsi = Indicators.rsi(
                closes,
                RSI_PERIOD
        );

        var stochRsi = Indicators.stochasticRsi(
                rsi,
                STOCH_RSI_PERIOD
        );

        var k = Indicators.sma(
                stochRsi,
                K_PERIOD
        );

        var d = Indicators.sma(
                k,
                D_PERIOD
        );

        for (int i = 0; i < closedKlines.size(); i++) {

            if (Double.isNaN(rsi.get(i))
                    || Double.isNaN(stochRsi.get(i))
                    || Double.isNaN(k.get(i))
                    || Double.isNaN(d.get(i))) {

                continue;
            }

            System.out.printf(
                    "%s | Close: %.2f | RSI: %.2f | StochRSI: %.4f | K: %.4f | D: %.4f%n",
                    java.time.Instant.ofEpochMilli(
                            closedKlines.get(i).closeTime()
                    ),
                    closedKlines.get(i).close(),
                    rsi.get(i),
                    stochRsi.get(i),
                    k.get(i),
                    d.get(i)
            );
        }
    }
}