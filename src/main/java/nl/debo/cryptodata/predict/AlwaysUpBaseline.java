package nl.debo.cryptodata.predict;

/**
 * Predicts "up" for every candle. Its accuracy equals the market's up-rate,
 * the floor any real model has to beat.
 */
public final class AlwaysUpBaseline implements Predictor {

    @Override
    public String name() {
        return "alwaysUp";
    }

    @Override
    public void fit(double[][] x, int[] y) {
    }

    @Override
    public double predictProbability(double[] features) {
        return 1.0;
    }
}
