package nl.debo.cryptodata.predict;

/**
 * A model that predicts the probability of the "up" label. Baselines and
 * real models implement the same interface, so the backtest loop treats them
 * identically.
 */
public interface Predictor {

    String name();

    /**
     * Fully resets internal state and trains on the given chronological
     * rows. Called repeatedly with growing windows during a walk-forward
     * backtest.
     */
    void fit(double[][] x, int[] y);

    /** Probability that the label is {@code 1} (price goes up). */
    double predictProbability(double[] features);
}
