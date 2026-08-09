package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BitvavoClient;

import java.util.List;

/**
 * Entry point for the Bitvavo flavour of the {@link CryptoAnalysis} pipeline.
 * Bitvavo markets are dash-separated ({@code "SOL-EUR"}) and the weekly
 * interval is spelled with a capital W ({@code "1W"}).
 */
public final class CryptoAnalysisBitvavo {

    private static final List<String> INTERVALS = List.of("1h", "1d", "1W", "1M");

    private CryptoAnalysisBitvavo() {
    }

    public static void main(String[] args) throws Exception {
        new CryptoAnalysis(
                new BitvavoClient(),
                "symbols-bitvavo",
                INTERVALS,
                CryptoAnalysisBitvavo::baseAsset,
                "data-bitvavo"
        ).run();
    }

    /** Extracts the base coin from a market, e.g. {@code "SOL-EUR" -> "SOL"}. */
    private static String baseAsset(String market) {
        int dash = market.indexOf('-');
        return dash > 0 ? market.substring(0, dash) : market;
    }
}
