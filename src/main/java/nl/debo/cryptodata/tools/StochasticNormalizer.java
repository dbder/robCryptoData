package nl.debo.cryptodata.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stochastic-style normalization: the position of the current value within
 * its own min-max range over the window, like
 * {@link Indicators#stochasticRsi}. 0 = the lowest value seen in the window,
 * 1 = the highest. A zero-width range yields the neutral 0.5.
 */
public final class StochasticNormalizer implements SignalNormalizer {

    private final int window;

    public StochasticNormalizer(int window) {
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

            double lowest = Double.POSITIVE_INFINITY;
            double highest = Double.NEGATIVE_INFINITY;

            boolean valid = true;

            for (int j = i - window + 1; j <= i; j++) {

                double value = values.get(j);

                if (Double.isNaN(value)) {
                    valid = false;
                    break;
                }

                lowest = Math.min(lowest, value);
                highest = Math.max(highest, value);
            }

            if (!valid) {
                continue;
            }

            double range = highest - lowest;

            if (range == 0) {
                result.set(i, 0.5);
            } else {
                result.set(i, (current - lowest) / range);
            }
        }

        return result;
    }

    @Override
    public String name() {
        return "stochastic(" + window + ")";
    }
}
