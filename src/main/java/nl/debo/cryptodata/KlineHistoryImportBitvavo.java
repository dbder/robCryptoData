package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BitvavoClient;
import nl.debo.cryptodata.tools.KlineHistoryImporter;
import nl.debo.cryptodata.utils.ConsoleColor;
import nl.debo.cryptodata.utils.FileUtil;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Entry point: downloads candlestick history for every Bitvavo market/interval
 * combination into {@code output/klines-bitvavo/} (one CSV per combination).
 * The actual import logic lives in {@link KlineHistoryImporter}, which other
 * tools use to update single combinations on demand; this entry point fans it
 * out over all markets and intervals. Errors are reported per combination and
 * never break the run.
 *
 * <p>Run directly from the IDE; the jar's default main class remains
 * {@link CryptoAnalysisBitvavo}.</p>
 */
public final class KlineHistoryImportBitvavo {

    private static final List<String> INTERVALS =
            List.of("1h", "2h", "4h", "6h", "8h", "12h", "1d", "1W", "1M");

    private KlineHistoryImportBitvavo() {
    }

    public static void main(String[] args) throws Exception {
        Path appDir = FileUtil.applicationDir();
        List<String> markets = FileUtil.readLinesWithFallback(
                KlineHistoryImportBitvavo.class,
                "symbols-bitvavo",
                appDir.resolve("symbols-bitvavo"),
                Path.of("src/main/resources/nl/debo/cryptodata/symbols-bitvavo"),
                Path.of("symbols-bitvavo")
        );

        List<String> activeMarkets = markets.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();

        Path klinesDir = appDir.resolve("output/klines-bitvavo");
        var importer = new KlineHistoryImporter(new BitvavoClient(), klinesDir);

        System.out.println(ConsoleColor.green(
                "Bitvavo kline history import started: " + activeMarkets.size() + " markets x "
                        + INTERVALS.size() + " intervals -> " + klinesDir));

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> futures = activeMarkets.stream()
                    .flatMap(market -> INTERVALS.stream().map(interval ->
                            CompletableFuture.runAsync(
                                    () -> updateQuietly(importer, market, interval),
                                    executor)))
                    .toList();

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        System.out.println(ConsoleColor.green("Bitvavo kline history import finished."));
    }

    private static void updateQuietly(KlineHistoryImporter importer, String market, String interval) {
        try {
            importer.update(market, interval);
        } catch (Exception e) {
            System.err.println(ConsoleColor.orange(
                    "Error importing " + market + " (" + interval + "): " + e.getMessage()));
        }
    }
}
