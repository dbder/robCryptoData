package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BinanceClient;

import java.util.List;

/**
 * Entry point for the Binance flavour of the {@link CryptoAnalysis} pipeline.
 * Binance symbols concatenate base and quote asset ({@code "SOLEUR"}).
 */
public final class CryptoAnalysisBinance {

    /** Quote assets stripped from a pair symbol to get the base coin. */
    private static final List<String> QUOTE_ASSETS =
            List.of("USDT", "USDC", "BUSD", "EUR", "USD", "BTC", "ETH", "BNB");

    private static final List<String> INTERVALS = List.of("1h", "1d", "1w", "1M");

    private CryptoAnalysisBinance() {
    }

    public static void main(String[] args) throws Exception {
        new CryptoAnalysis(
                new BinanceClient(),
                "symbols",
                INTERVALS,
                CryptoAnalysisBinance::baseAsset,
                "data"
        ).run();
    }

    /**
     * Strips a known quote asset suffix from a pair symbol, e.g.
     * {@code "SOLEUR" -> "SOL"}. Returns the symbol unchanged if no known
     * quote asset matches.
     */
    private static String baseAsset(String symbol) {
        for (var quote : QUOTE_ASSETS) {
            if (symbol.length() > quote.length() && symbol.endsWith(quote)) {
                return symbol.substring(0, symbol.length() - quote.length());
            }
        }
        return symbol;
    }
}
