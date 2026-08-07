package nl.debo.cryptodata;

import nl.debo.cryptodata.predict.AlwaysUpBaseline;
import nl.debo.cryptodata.predict.Dataset;
import nl.debo.cryptodata.predict.IndicatorFeatures;
import nl.debo.cryptodata.predict.LogisticRegression;
import nl.debo.cryptodata.predict.NextDayDirectionLabeler;
import nl.debo.cryptodata.predict.SameDirectionBaseline;
import nl.debo.cryptodata.predict.WalkForwardBacktest;
import nl.debo.cryptodata.predict.WalkForwardBacktest.ModelResult;
import nl.debo.cryptodata.utils.FileUtil;
import nl.debo.cryptodata.utils.KlineCsvStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Entry point: walk-forward backtest of next-day direction predictions on
 * the daily candles saved by {@link KlineHistoryImport}. Per symbol, a
 * logistic-regression model is compared against two baselines ("always up"
 * and "same direction as yesterday"); results go to the console and to
 * {@code output/predictions_<date>.csv}. Run directly from the IDE.
 */
public final class PredictionBacktest {

    private static final String INTERVAL = "1d";
    private static final int MIN_TRAIN_SIZE = 250;
    private static final int REFIT_INTERVAL = 30;
    /** Minimum usable rows so a symbol yields at least ~50 out-of-sample predictions. */
    private static final int MIN_DATASET_ROWS = 300;
    private static final double LEARNING_RATE = 0.1;
    private static final int ITERATIONS = 500;

    private record SymbolReport(String symbol, int candles, int rows,
                                ModelResult model, ModelResult alwaysUp, ModelResult sameDir) {

        boolean beatsBoth() {
            return model.accuracy() > alwaysUp.accuracy() && model.accuracy() > sameDir.accuracy();
        }
    }

    private PredictionBacktest() {
    }

    public static void main(String[] args) throws Exception {
        Path appDir = FileUtil.applicationDir();
        List<String> symbols = FileUtil.readLinesWithFallback(
                CryptoAnalysis.class,
                "symbols",
                appDir.resolve("symbols"),
                Path.of("src/main/resources/nl/debo/cryptodata/symbols"),
                Path.of("symbols")
        );

        List<String> activeSymbols = symbols.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();

        var extractor = new IndicatorFeatures();
        var labeler = new NextDayDirectionLabeler();
        var backtest = new WalkForwardBacktest(MIN_TRAIN_SIZE, REFIT_INTERVAL);

        System.out.printf("%-12s %8s %6s %6s %7s %8s %10s %9s %s%n",
                "SYMBOL", "CANDLES", "ROWS", "PREDS", "MODEL", "LOGLOSS", "ALWAYS_UP", "SAME_DIR", "BEATS_BOTH");

        var reports = new ArrayList<SymbolReport>();
        int skipped = 0;
        for (String symbol : activeSymbols) {
            Path csvPath = appDir.resolve("output/klines/" + symbol + "_" + INTERVAL + ".csv");
            if (!Files.exists(csvPath)) {
                System.err.println(symbol + ": no " + INTERVAL + " history, skipping");
                skipped++;
                continue;
            }
            try {
                var klines = KlineCsvStore.readKlines(csvPath);
                var dataset = Dataset.build(klines, extractor, labeler);
                if (dataset.size() < MIN_DATASET_ROWS) {
                    System.err.println(symbol + ": only " + dataset.size() + " usable rows, skipping");
                    skipped++;
                    continue;
                }

                var model = backtest.evaluate(dataset, new LogisticRegression(LEARNING_RATE, ITERATIONS));
                var alwaysUp = backtest.evaluate(dataset, new AlwaysUpBaseline());
                var sameDir = backtest.evaluate(dataset,
                        new SameDirectionBaseline(dataset.featureIndex("ret1")));

                var report = new SymbolReport(symbol, klines.size(), dataset.size(), model, alwaysUp, sameDir);
                printReport(report);
                reports.add(report);
            } catch (Exception e) {
                System.err.println("Error backtesting " + symbol + ": " + e.getMessage());
                skipped++;
            }
        }

        printAggregate(reports, skipped);
        writeCsv(appDir.resolve("output/predictions_" + LocalDate.now() + ".csv"), reports);
    }

    private static void printReport(SymbolReport r) {
        System.out.printf(Locale.US, "%-12s %8d %6d %6d %7.4f %8.4f %10.4f %9.4f %s%n",
                r.symbol(),
                r.candles(),
                r.rows(),
                r.model().predictions(),
                r.model().accuracy(),
                r.model().meanLogLoss(),
                r.alwaysUp().accuracy(),
                r.sameDir().accuracy(),
                r.beatsBoth() ? "yes" : "no");
    }

    private static void printAggregate(List<SymbolReport> reports, int skipped) {
        if (skipped > 0) {
            System.out.println("Skipped " + skipped + " symbols (missing or insufficient history).");
        }
        if (reports.isEmpty()) {
            System.out.println("No symbols evaluated.");
            return;
        }

        long predictions = reports.stream().mapToLong(r -> r.model().predictions()).sum();
        long beatsBoth = reports.stream().filter(SymbolReport::beatsBoth).count();
        System.out.printf(Locale.US,
                "AGGREGATE (%d symbols, %d predictions): model %.4f | always-up %.4f | same-dir %.4f"
                        + " | model beats both on %d/%d symbols%n",
                reports.size(),
                predictions,
                weightedAccuracy(reports, SymbolReport::model),
                weightedAccuracy(reports, SymbolReport::alwaysUp),
                weightedAccuracy(reports, SymbolReport::sameDir),
                beatsBoth,
                reports.size());
    }

    /** Total correct over total predictions, so long histories weigh heavier. */
    private static double weightedAccuracy(List<SymbolReport> reports,
                                           Function<SymbolReport, ModelResult> result) {
        long correct = reports.stream().mapToLong(r -> result.apply(r).correct()).sum();
        long predictions = reports.stream().mapToLong(r -> result.apply(r).predictions()).sum();
        return predictions == 0 ? Double.NaN : (double) correct / predictions;
    }

    private static void writeCsv(Path csvPath, List<SymbolReport> reports) {
        try (var writer = Files.newBufferedWriter(csvPath, StandardCharsets.UTF_8)) {
            writer.write("symbol,candles,datasetRows,predictions,modelAccuracy,modelLogLoss,"
                    + "alwaysUpAccuracy,sameDirAccuracy,modelBeatsBoth");
            writer.newLine();
            for (var r : reports) {
                writer.write(String.format(Locale.US, "%s,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%s",
                        r.symbol(),
                        r.candles(),
                        r.rows(),
                        r.model().predictions(),
                        r.model().accuracy(),
                        r.model().meanLogLoss(),
                        r.alwaysUp().accuracy(),
                        r.sameDir().accuracy(),
                        r.beatsBoth() ? "yes" : "no"));
                writer.newLine();
            }
            if (!reports.isEmpty()) {
                long candles = reports.stream().mapToLong(SymbolReport::candles).sum();
                long rows = reports.stream().mapToLong(SymbolReport::rows).sum();
                long predictions = reports.stream().mapToLong(r -> r.model().predictions()).sum();
                long beatsBoth = reports.stream().filter(SymbolReport::beatsBoth).count();
                writer.write(String.format(Locale.US, "TOTAL,%d,%d,%d,%.4f,,%.4f,%.4f,%d/%d",
                        candles,
                        rows,
                        predictions,
                        weightedAccuracy(reports, SymbolReport::model),
                        weightedAccuracy(reports, SymbolReport::alwaysUp),
                        weightedAccuracy(reports, SymbolReport::sameDir),
                        beatsBoth,
                        reports.size()));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing " + csvPath + ": " + e.getMessage());
        }
    }
}
