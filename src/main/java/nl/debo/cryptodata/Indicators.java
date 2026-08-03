package nl.debo.cryptodata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Indicators {

    private Indicators() {
    }

    public static List<Double> rsi(
            List<Double> prices,
            int period
    ) {

        if (prices.size() <= period) {
            return Collections.emptyList();
        }

        var result = new ArrayList<Double>(
                Collections.nCopies(prices.size(), Double.NaN)
        );

        double gainSum = 0;
        double lossSum = 0;

        // Initial average gain/loss
        for (int i = 1; i <= period; i++) {

            double change = prices.get(i) - prices.get(i - 1);

            if (change > 0) {
                gainSum += change;
            } else {
                lossSum -= change;
            }
        }

        double averageGain = gainSum / period;
        double averageLoss = lossSum / period;

        result.set(
                period,
                calculateRsi(averageGain, averageLoss)
        );

        // Wilder smoothing
        for (int i = period + 1; i < prices.size(); i++) {

            double change =
                    prices.get(i) - prices.get(i - 1);

            double gain = Math.max(change, 0);
            double loss = Math.max(-change, 0);

            averageGain =
                    ((averageGain * (period - 1)) + gain)
                            / period;

            averageLoss =
                    ((averageLoss * (period - 1)) + loss)
                            / period;

            result.set(
                    i,
                    calculateRsi(
                            averageGain,
                            averageLoss
                    )
            );
        }

        return result;
    }

    private static double calculateRsi(
            double averageGain,
            double averageLoss
    ) {

        if (averageLoss == 0) {
            return 100.0;
        }

        if (averageGain == 0) {
            return 0.0;
        }

        double rs = averageGain / averageLoss;

        return 100.0 - (100.0 / (1.0 + rs));
    }

    public static List<Double> stochasticRsi(
            List<Double> rsi,
            int period
    ) {

        var result = new ArrayList<Double>(
                Collections.nCopies(rsi.size(), Double.NaN)
        );

        for (int i = period - 1; i < rsi.size(); i++) {

            double current = rsi.get(i);

            if (Double.isNaN(current)) {
                continue;
            }

            double lowest = Double.POSITIVE_INFINITY;
            double highest = Double.NEGATIVE_INFINITY;

            boolean valid = true;

            for (int j = i - period + 1; j <= i; j++) {

                double value = rsi.get(j);

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
                result.set(i, 0.0);
            } else {
                result.set(
                        i,
                        (current - lowest) / range
                );
            }
        }

        return result;
    }

    public static List<Double> sma(
            List<Double> values,
            int period
    ) {

        var result = new ArrayList<Double>(
                Collections.nCopies(values.size(), Double.NaN)
        );

        double sum = 0;

        for (int i = 0; i < values.size(); i++) {

            double value = values.get(i);

            if (Double.isNaN(value)) {
                continue;
            }

            sum += value;

            if (i >= period) {

                double oldValue = values.get(i - period);

                if (!Double.isNaN(oldValue)) {
                    sum -= oldValue;
                }
            }

            if (i >= period - 1) {

                boolean valid = true;

                for (int j = i - period + 1; j <= i; j++) {
                    if (Double.isNaN(values.get(j))) {
                        valid = false;
                        break;
                    }
                }

                if (valid) {
                    result.set(i, sum / period);
                }
            }
        }

        return result;
    }
}
