package nl.debo.cryptodata.predict;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;
import java.util.Optional;

/**
 * Turns a kline series into the numeric inputs a model learns from. Adding
 * or changing features means a new implementation; the rest of the pipeline
 * is untouched.
 */
public interface FeatureExtractor {

    /** Names of the features, in the same order as the columns of {@link #extract}. */
    List<String> featureNames();

    /**
     * Feature columns, {@code [featureNames().size()][klines.size()]},
     * aligned index-by-index with the klines. Cells where a feature is not
     * yet defined (indicator warm-up) are {@code NaN}.
     */
    double[][] extract(List<Kline> klines);

    /**
     * Feature row of the newest candle — the inputs for a real forward
     * prediction about the candle after it. Empty if the series is too short
     * or any feature is still NaN.
     */
    default Optional<double[]> latestFeatures(List<Kline> klines) {
        if (klines.isEmpty()) {
            return Optional.empty();
        }
        double[][] columns = extract(klines);
        int last = klines.size() - 1;
        double[] row = new double[columns.length];
        for (int f = 0; f < columns.length; f++) {
            row[f] = columns[f][last];
            if (Double.isNaN(row[f])) {
                return Optional.empty();
            }
        }
        return Optional.of(row);
    }
}
