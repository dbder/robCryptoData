package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.CryptoAnalysis;
import nl.debo.cryptodata.tools.LocalKlineSource;
import nl.debo.cryptodata.tools.PairSymbols;
import nl.debo.cryptodata.utils.FileUtil;

import java.util.List;

/**
 * Entry point: the {@link CryptoAnalysis} pipeline fed from the local candle
 * store instead of the live API. First brings {@code output/klines-bitvavo/}
 * up to date via {@link KlineHistoryImportBitvavo}, then builds the sheets
 * from those CSVs through a {@link LocalKlineSource}.
 *
 * <p>Reports land in {@code output/data-bitvavo-local<date>.csv/.xlsx}, so a
 * run of {@link CryptoAnalysisBitvavo} on the same day can be compared
 * against them one to one.</p>
 */
public final class CryptoAnalysisBitvavoFromLocal {

    private static final List<String> INTERVALS = List.of("1d", "1W", "1M");

    private CryptoAnalysisBitvavoFromLocal() {
    }

    public static void main(String[] args) throws Exception {
        KlineHistoryImportBitvavo.main(args);

        new CryptoAnalysis(
                new LocalKlineSource(FileUtil.applicationDir().resolve("output/klines-bitvavo")),
                "symbols-bitvavo",
                INTERVALS,
                PairSymbols::base,
                "data-bitvavo-local"
        ).run();
    }
}
