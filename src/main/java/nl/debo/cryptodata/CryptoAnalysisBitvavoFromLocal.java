package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BitvavoClient;
import nl.debo.cryptodata.tools.CryptoAnalysis;
import nl.debo.cryptodata.tools.KlineHistoryImporter;
import nl.debo.cryptodata.tools.LocalKlineSource;
import nl.debo.cryptodata.tools.PairSymbols;
import nl.debo.cryptodata.utils.FileUtil;

import java.nio.file.Path;
import java.util.List;

/**
 * Entry point: the {@link CryptoAnalysis} pipeline fed from the local candle
 * store instead of the live API. The {@link LocalKlineSource} carries a
 * {@link KlineHistoryImporter}, so each requested market/interval CSV in
 * {@code output/klines-bitvavo/} is created or brought up to date on demand —
 * only the combinations the analysis actually uses are touched. Run
 * {@link KlineHistoryImportBitvavo} to refresh the whole store.
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
        Path klinesDir = FileUtil.applicationDir().resolve("output/klines-bitvavo");

        new CryptoAnalysis(
                new LocalKlineSource(klinesDir, new KlineHistoryImporter(new BitvavoClient(), klinesDir)),
                "symbols-bitvavo",
                INTERVALS,
                PairSymbols::base,
                "data-bitvavo-local"
        ).run();
    }
}
