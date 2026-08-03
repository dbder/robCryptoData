package nl.debo.cryptodata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class CryptoAnalysis {

    private static final int RSI_PERIOD = 14;
    private static final int STOCH_RSI_PERIOD = 14;
    private static final int K_PERIOD = 3;
    private static final int D_PERIOD = 3;

    public static void main(String[] args) throws Exception {
        var client = new BinanceClient();

        List<String> symbols;
        Path path = Path.of("src/main/java/nl/debo/cryptodata/symbols");
        if (!Files.exists(path)) {
            path = Path.of("symbols");
        }
        if (Files.exists(path)) {
            symbols = Files.readAllLines(path);
        } else {
            var resource = CryptoAnalysis.class.getResourceAsStream("symbols");
            if (resource != null) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8))) {
                    symbols = reader.lines().toList();
                }
            } else {
                throw new IOException("Symbols file not found");
            }
        }

        for (String rawSymbol : symbols) {
            var symbol = rawSymbol.trim();
            if (symbol.isEmpty() || symbol.startsWith("#")) {
                continue;
            }

            try {
                var klines = client.getKlines(
                        symbol,
                        "1h",
                        200
                );

                if (klines.size() <= 1) {
                    continue;
                }

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
                            "%s | %s | Close: %.2f | RSI: %.2f | StochRSI: %.4f | K: %.4f | D: %.4f%n",
                            symbol,
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

                Thread.sleep(100);
            } catch (Exception e) {
                System.err.println("Error processing symbol " + symbol + ": " + e.getMessage());
            }
        }
    }
}
