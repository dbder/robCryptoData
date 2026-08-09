package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BinanceClient;
import nl.debo.cryptodata.tools.PairSymbols;

import java.util.List;

/**
 * Entry point for the Binance flavour of the {@link CryptoAnalysis} pipeline.
 * Binance symbols concatenate base and quote asset ({@code "SOLEUR"}).
 */
public final class CryptoAnalysisBinance {

    private static final List<String> INTERVALS = List.of("1d", "1w", "1M");

    private CryptoAnalysisBinance() {
    }

    public static void main(String[] args) throws Exception {
        new CryptoAnalysis(
                new BinanceClient(),
                "symbols",
                INTERVALS,
                PairSymbols::base,
                "data"
        ).run();
    }
}
