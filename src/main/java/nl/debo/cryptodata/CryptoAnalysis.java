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

        List<String> intervals = List.of(
                "1h",
                "1d",
                "1w",
                "1M"
        );

        var dateStr = java.time.LocalDate.now().toString();
        var csvPath = Path.of("data" + dateStr + ".csv");
        var header = "symbol,interval,time,close,rsi,stochRsi,k,d";
        if (!Files.exists(csvPath)) {
            Files.writeString(csvPath, header + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE);
        }

        for (String rawSymbol : symbols) {
            var symbol = rawSymbol.trim();
            if (symbol.isEmpty() || symbol.startsWith("#")) {
                continue;
            }

            for (String interval : intervals) {
                try {
                    var klines = client.getKlines(
                            symbol,
                            interval,
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

                    if (rsi.isEmpty() || stochRsi.isEmpty() || k.isEmpty() || d.isEmpty()) {
                        continue;
                    }

                    for (int i = closedKlines.size() - 1; i >= 0; i--) {

                        if (i >= rsi.size() || i >= stochRsi.size() || i >= k.size() || i >= d.size()) {
                            continue;
                        }

                        if (Double.isNaN(rsi.get(i))
                                || Double.isNaN(stochRsi.get(i))
                                || Double.isNaN(k.get(i))
                                || Double.isNaN(d.get(i))) {

                            continue;
                        }

                        var timeInst = java.time.Instant.ofEpochMilli(
                                closedKlines.get(i).closeTime()
                        );
                        System.out.printf(
                                "%s | %s | %s | Close: %.2f | RSI: %.2f | StochRSI: %.4f | K: %.4f | D: %.4f%n",
                                symbol,
                                interval,
                                timeInst,
                                closedKlines.get(i).close(),
                                rsi.get(i),
                                stochRsi.get(i),
                                k.get(i),
                                d.get(i)
                        );
                        var csvLine = String.format(
                                "%s,%s,%s,%.2f,%.4f,%.4f,%.4f,%.4f",
                                symbol,
                                interval,
                                timeInst,
                                closedKlines.get(i).close(),
                                rsi.get(i),
                                stochRsi.get(i),
                                k.get(i),
                                d.get(i)
                        );
                        Files.writeString(csvPath, csvLine + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        break;
                    }

                    Thread.sleep(50);
                } catch (Exception e) {
                    System.err.println("Error processing symbol " + symbol + " (" + interval + "): " + e.getMessage());
                }
            }
        }
    }
}
