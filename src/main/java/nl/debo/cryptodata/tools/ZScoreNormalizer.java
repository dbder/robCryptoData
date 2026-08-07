package nl.debo.cryptodata.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Volatility-adaptive normalization: divides each value by the rolling
 * standard deviation of the values over the window and squashes the resulting
 * z-score with a logistic curve. At zero the result is 0.5, one sigma above
 * ~0.73, two sigma ~0.88; the curve approaches but never reaches 0 and 1.
 * A zero standard deviation (flat window) yields the neutral 0.5.
 */
public final class ZScoreNormalizer implements SignalNormalizer {

    private final int window;

    public ZScoreNormalizer(int window) {
        this.window = window;
    }

    @Override
    public List<Double> normalize(List<Double> values) {

        var result = new ArrayList<Double>(
                Collections.nCopies(values.size(), Double.NaN)
        );

        for (int i = window - 1; i < values.size(); i++) {

            double current = values.get(i);

            if (Double.isNaN(current)) {
                continue;
            }

            double sum = 0;
            boolean valid = true;

            for (int j = i - window + 1; j <= i; j++) {

                double value = values.get(j);

                if (Double.isNaN(value)) {
                    valid = false;
                    break;
                }

                sum += value;
            }

            if (!valid) {
                continue;
            }

            double mean = sum / window;

            double squaredSum = 0;

            for (int j = i - window + 1; j <= i; j++) {
                double delta = values.get(j) - mean;
                squaredSum += delta * delta;
            }

            double std = Math.sqrt(squaredSum / window);

            if (std == 0) {
                result.set(i, 0.5);
            } else {
                double z = current / std;
                result.set(i, 1.0 / (1.0 + Math.exp(-z)));
            }
        }

        return result;
    }

    @Override
    public String name() {
        return "z-score(" + window + ")";
    }
}
