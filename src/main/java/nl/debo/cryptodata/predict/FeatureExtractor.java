package nl.debo.cryptodata.predict;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;

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
}
