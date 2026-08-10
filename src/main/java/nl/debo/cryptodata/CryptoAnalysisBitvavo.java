package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BitvavoClient;
import nl.debo.cryptodata.tools.CryptoAnalysis;
import nl.debo.cryptodata.tools.PairSymbols;

import java.util.List;

/**
 * Entry point for the Bitvavo flavour of the {@link CryptoAnalysis} pipeline.
 * Bitvavo markets are dash-separated ({@code "SOL-EUR"}) and the weekly
 * interval is spelled with a capital W ({@code "1W"}).
 */
public final class CryptoAnalysisBitvavo {

    private static final List<String> INTERVALS = List.of("1d", "1W", "1M");

    private CryptoAnalysisBitvavo() {
    }

    public static void main(String[] args) throws Exception {
        new CryptoAnalysis(
                new BitvavoClient(),
                "symbols-bitvavo",
                INTERVALS,
                PairSymbols::base,
                "data-bitvavo"
        ).run();
    }
}
