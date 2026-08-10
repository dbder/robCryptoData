package nl.debo.cryptodata;

import nl.debo.cryptodata.predict.AlwaysUpBaseline;
import nl.debo.cryptodata.predict.Dataset;
import nl.debo.cryptodata.predict.IndicatorFeatures;
import nl.debo.cryptodata.predict.LogisticRegression;
import nl.debo.cryptodata.predict.NextDayDirectionLabeler;
import nl.debo.cryptodata.predict.SameDirectionBaseline;
import nl.debo.cryptodata.predict.TradingSimulation;
import nl.debo.cryptodata.predict.TradingSimulation.TradingResult;
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
 * the daily candles saved by {@link KlineHistoryImportBitvavo}. Per symbol, a
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
                                ModelResult model, ModelResult alwaysUp, ModelResult sameDir,
                                double upProbability, TradingResult taker, TradingResult maker) {

        boolean beatsBoth() {
            return model.accuracy() > alwaysUp.accuracy() && model.accuracy() > sameDir.accuracy();
        }

        /** Forward prediction for the candle after the newest saved one. */
        String prediction() {
            return Double.isNaN(upProbability) ? "-" : upProbability >= 0.5 ? "UP" : "DOWN";
        }
    }

    private PredictionBacktest() {
    }

    public static void main(String[] args) throws Exception {
        Path appDir = FileUtil.applicationDir();
        List<String> symbols = FileUtil.readLinesWithFallback(
                PredictionBacktest.class,
                "symbols-bitvavo",
                appDir.resolve("symbols-bitvavo"),
                Path.of("src/main/resources/nl/debo/cryptodata/symbols-bitvavo"),
                Path.of("symbols-bitvavo")
        );

        List<String> activeSymbols = symbols.stream()
                .map(String::trim)
                .filter(s -> !s.isEmpty() && !s.startsWith("#"))
                .toList();

        var extractor = new IndicatorFeatures();
        var labeler = new NextDayDirectionLabeler();
        var backtest = new WalkForwardBacktest(MIN_TRAIN_SIZE, REFIT_INTERVAL);

        System.out.printf("%-12s %8s %6s %6s %7s %8s %10s %9s %-10s %-8s %7s %10s %10s %10s %6s%n",
                "SYMBOL", "CANDLES", "ROWS", "PREDS", "MODEL", "LOGLOSS", "ALWAYS_UP", "SAME_DIR",
                "BEATS_BOTH", "TOMORROW", "UP_PROB", "NET_TAKER", "NET_MAKER", "BUY_HOLD", "TRADES");

        var reports = new ArrayList<SymbolReport>();
        int skipped = 0;
        for (String symbol : activeSymbols) {
            Path csvPath = appDir.resolve("output/klines-bitvavo/" + symbol + "_" + INTERVAL + ".csv");
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

                // Actual forward prediction: train on the full history, then
                // predict the direction of the candle after the newest one.
                var predictor = new LogisticRegression(LEARNING_RATE, ITERATIONS);
                predictor.fit(dataset.rows(), dataset.labels());
                double upProbability = extractor.latestFeatures(klines)
                        .map(predictor::predictProbability)
                        .orElse(Double.NaN);

                var taker = TradingSimulation.simulate(dataset.nextReturns(), model.probabilities(),
                        MIN_TRAIN_SIZE, takerFee(symbol));
                var maker = TradingSimulation.simulate(dataset.nextReturns(), model.probabilities(),
                        MIN_TRAIN_SIZE, makerFee(symbol));

                var report = new SymbolReport(symbol, klines.size(), dataset.size(),
                        model, alwaysUp, sameDir, upProbability, taker, maker);
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
        System.out.printf(Locale.US,
                "%-12s %8d %6d %6d %7.4f %8.4f %10.4f %9.4f %-10s %-8s %7.4f %10s %10s %10s %6d%n",
                r.symbol(),
                r.candles(),
                r.rows(),
                r.model().predictions(),
                r.model().accuracy(),
                r.model().meanLogLoss(),
                r.alwaysUp().accuracy(),
                r.sameDir().accuracy(),
                r.beatsBoth() ? "yes" : "no",
                r.prediction(),
                r.upProbability(),
                percent(r.taker().netReturn()),
                percent(r.maker().netReturn()),
                percent(r.taker().buyHoldReturn()),
                r.taker().trades());
    }

    private static String percent(double fraction) {
        return String.format(Locale.US, "%+.1f%%", fraction * 100);
    }

    /** Bitvavo default-tier taker fee per trade, by quote market (bitvavo.com/en/fees). */
    private static double takerFee(String symbol) {
        if (symbol.endsWith("USDC")) {
            return 0.0005;
        }
        if (symbol.endsWith("USDT")) {
            return 0.0010;
        }
        return 0.0025;
    }

    /** Bitvavo default-tier maker fee per trade, by quote market (bitvavo.com/en/fees). */
    private static double makerFee(String symbol) {
        if (symbol.endsWith("USDC")) {
            return 0.0005;
        }
        if (symbol.endsWith("USDT")) {
            return 0.0010;
        }
        return 0.0015;
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

        long positiveTaker = reports.stream().filter(r -> r.taker().netReturn() > 0).count();
        long positiveMaker = reports.stream().filter(r -> r.maker().netReturn() > 0).count();
        long beatsBuyHoldTaker = reports.stream()
                .filter(r -> r.taker().netReturn() > r.taker().buyHoldReturn()).count();
        long beatsBuyHoldMaker = reports.stream()
                .filter(r -> r.maker().netReturn() > r.maker().buyHoldReturn()).count();
        System.out.printf(Locale.US,
                "TRADING taker: median net %s vs median buy&hold %s | positive on %d/%d | beats buy&hold on %d/%d%n",
                percent(median(reports, r -> r.taker().netReturn())),
                percent(median(reports, r -> r.taker().buyHoldReturn())),
                positiveTaker, reports.size(),
                beatsBuyHoldTaker, reports.size());
        System.out.printf(Locale.US,
                "TRADING maker: median net %s vs median buy&hold %s | positive on %d/%d | beats buy&hold on %d/%d%n",
                percent(median(reports, r -> r.maker().netReturn())),
                percent(median(reports, r -> r.maker().buyHoldReturn())),
                positiveMaker, reports.size(),
                beatsBuyHoldMaker, reports.size());
    }

    private static double median(List<SymbolReport> reports,
                                 java.util.function.ToDoubleFunction<SymbolReport> value) {
        double[] sorted = reports.stream().mapToDouble(value).sorted().toArray();
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
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
                    + "alwaysUpAccuracy,sameDirAccuracy,modelBeatsBoth,nextDayPrediction,nextDayUpProbability,"
                    + "netReturnTaker,netReturnMaker,buyHoldReturn,trades");
            writer.newLine();
            for (var r : reports) {
                writer.write(String.format(Locale.US, "%s,%d,%d,%d,%.4f,%.4f,%.4f,%.4f,%s,%s,%.4f,%.4f,%.4f,%.4f,%d",
                        r.symbol(),
                        r.candles(),
                        r.rows(),
                        r.model().predictions(),
                        r.model().accuracy(),
                        r.model().meanLogLoss(),
                        r.alwaysUp().accuracy(),
                        r.sameDir().accuracy(),
                        r.beatsBoth() ? "yes" : "no",
                        r.prediction(),
                        r.upProbability(),
                        r.taker().netReturn(),
                        r.maker().netReturn(),
                        r.taker().buyHoldReturn(),
                        r.taker().trades()));
                writer.newLine();
            }
            if (!reports.isEmpty()) {
                long candles = reports.stream().mapToLong(SymbolReport::candles).sum();
                long rows = reports.stream().mapToLong(SymbolReport::rows).sum();
                long predictions = reports.stream().mapToLong(r -> r.model().predictions()).sum();
                long beatsBoth = reports.stream().filter(SymbolReport::beatsBoth).count();
                long trades = reports.stream().mapToLong(r -> r.taker().trades()).sum();
                writer.write(String.format(Locale.US, "TOTAL,%d,%d,%d,%.4f,,%.4f,%.4f,%d/%d,,,%.4f,%.4f,%.4f,%d",
                        candles,
                        rows,
                        predictions,
                        weightedAccuracy(reports, SymbolReport::model),
                        weightedAccuracy(reports, SymbolReport::alwaysUp),
                        weightedAccuracy(reports, SymbolReport::sameDir),
                        beatsBoth,
                        reports.size(),
                        median(reports, r -> r.taker().netReturn()),
                        median(reports, r -> r.maker().netReturn()),
                        median(reports, r -> r.taker().buyHoldReturn()),
                        trades));
                writer.newLine();
            }
        } catch (IOException e) {
            System.err.println("Error writing " + csvPath + ": " + e.getMessage());
        }
    }
}
