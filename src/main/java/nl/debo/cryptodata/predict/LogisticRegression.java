package nl.debo.cryptodata.predict;

/**
 * Hand-rolled logistic regression trained with batch gradient descent.
 * Features are standardized with mean/std computed on the training rows
 * only; those statistics are stored and reapplied at predict time, so test
 * candles never influence the scaling (no look-ahead leakage).
 */
public final class LogisticRegression implements Predictor {

    private final double learningRate;
    private final int iterations;

    private double[] weights;
    private double bias;
    private double[] means;
    private double[] stds;

    public LogisticRegression(double learningRate, int iterations) {
        this.learningRate = learningRate;
        this.iterations = iterations;
    }

    @Override
    public String name() {
        return "logisticRegression";
    }

    @Override
    public void fit(double[][] x, int[] y) {
        int n = x.length;
        int featureCount = x[0].length;

        means = new double[featureCount];
        stds = new double[featureCount];
        for (int j = 0; j < featureCount; j++) {
            double sum = 0;
            for (double[] row : x) {
                sum += row[j];
            }
            means[j] = sum / n;

            double squaredSum = 0;
            for (double[] row : x) {
                double delta = row[j] - means[j];
                squaredSum += delta * delta;
            }
            double std = Math.sqrt(squaredSum / n);
            stds[j] = std > 0 && Double.isFinite(std) ? std : 1.0;
        }

        double[][] z = new double[n][featureCount];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < featureCount; j++) {
                z[i][j] = (x[i][j] - means[j]) / stds[j];
            }
        }

        weights = new double[featureCount];
        bias = 0;
        for (int iteration = 0; iteration < iterations; iteration++) {
            double[] gradient = new double[featureCount];
            double biasGradient = 0;
            for (int i = 0; i < n; i++) {
                double linear = bias;
                for (int j = 0; j < featureCount; j++) {
                    linear += weights[j] * z[i][j];
                }
                double error = sigmoid(linear) - y[i];
                for (int j = 0; j < featureCount; j++) {
                    gradient[j] += error * z[i][j];
                }
                biasGradient += error;
            }
            for (int j = 0; j < featureCount; j++) {
                weights[j] -= learningRate * gradient[j] / n;
            }
            bias -= learningRate * biasGradient / n;
        }
    }

    @Override
    public double predictProbability(double[] features) {
        double linear = bias;
        for (int j = 0; j < weights.length; j++) {
            linear += weights[j] * (features[j] - means[j]) / stds[j];
        }
        return Math.clamp(sigmoid(linear), 1e-9, 1 - 1e-9);
    }

    private static double sigmoid(double value) {
        return 1.0 / (1.0 + Math.exp(-value));
    }
}
