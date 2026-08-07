package nl.debo.cryptodata.predict;

import java.util.Arrays;

/**
 * Expanding-window walk-forward evaluation: every prediction is made by a
 * model trained exclusively on earlier candles, sliding forward through
 * time. Rows are never shuffled — a random train/test split would leak
 * future information and inflate accuracy.
 */
public final class WalkForwardBacktest {

    private final int minTrainSize;
    private final int refitInterval;

    /**
     * @param minTrainSize  candles required before the first prediction
     * @param refitInterval how often (in candles) the model is retrained on
     *                      the grown window; between refits it keeps
     *                      predicting with slightly stale weights
     */
    public WalkForwardBacktest(int minTrainSize, int refitInterval) {
        this.minTrainSize = minTrainSize;
        this.refitInterval = refitInterval;
    }

    /** Out-of-sample result of one model on one dataset. */
    public record ModelResult(String modelName, int predictions, int correct, double logLossSum) {

        public double accuracy() {
            return predictions == 0 ? Double.NaN : (double) correct / predictions;
        }

        public double meanLogLoss() {
            return predictions == 0 ? Double.NaN : logLossSum / predictions;
        }
    }

    public ModelResult evaluate(Dataset dataset, Predictor model) {
        int predictions = 0;
        int correct = 0;
        double logLossSum = 0;

        for (int t = minTrainSize; t < dataset.size(); t++) {
            if ((t - minTrainSize) % refitInterval == 0) {
                model.fit(
                        Arrays.copyOfRange(dataset.rows(), 0, t),
                        Arrays.copyOfRange(dataset.labels(), 0, t)
                );
            }

            double probability = model.predictProbability(dataset.rows()[t]);
            int predicted = probability >= 0.5 ? 1 : 0;
            int actual = dataset.labels()[t];

            if (predicted == actual) {
                correct++;
            }
            predictions++;

            double clamped = Math.clamp(probability, 1e-9, 1 - 1e-9);
            logLossSum += actual == 1 ? -Math.log(clamped) : -Math.log(1 - clamped);
        }

        return new ModelResult(model.name(), predictions, correct, logLossSum);
    }
}
