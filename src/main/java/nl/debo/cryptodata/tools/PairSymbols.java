package nl.debo.cryptodata.tools;

import java.util.List;

/**
 * Parses exchange pair symbols in the two formats used in this project:
 * dash-separated Bitvavo markets ({@code "SOL-EUR"}) and legacy concatenated
 * symbols ({@code "SOLEUR"}, {@code "SOLUSDT"}) that still occur in older
 * report CSVs from the removed Binance pipeline.
 */
public final class PairSymbols {

    /** Quote assets recognized as the suffix of a concatenated pair symbol. */
    private static final List<String> QUOTE_ASSETS =
            List.of("USDT", "USDC", "BUSD", "EUR", "USD", "BTC", "ETH", "BNB");

    private PairSymbols() {
    }

    /**
     * Base coin of a pair, e.g. {@code "SOL-EUR" -> "SOL"} or
     * {@code "SOLEUR" -> "SOL"}. Returns the symbol unchanged if no known
     * quote asset matches.
     */
    public static String base(String symbol) {
        int dash = symbol.indexOf('-');
        if (dash > 0) {
            return symbol.substring(0, dash);
        }
        for (var quote : QUOTE_ASSETS) {
            if (symbol.length() > quote.length() && symbol.endsWith(quote)) {
                return symbol.substring(0, symbol.length() - quote.length());
            }
        }
        return symbol;
    }

    /**
     * Quote asset of a pair, e.g. {@code "SOL-EUR" -> "EUR"} or
     * {@code "SOLUSDT" -> "USDT"}. Returns an empty string if no known
     * quote asset matches.
     */
    public static String quote(String symbol) {
        int dash = symbol.indexOf('-');
        if (dash > 0) {
            return symbol.substring(dash + 1);
        }
        for (var quote : QUOTE_ASSETS) {
            if (symbol.length() > quote.length() && symbol.endsWith(quote)) {
                return quote;
            }
        }
        return "";
    }
}
