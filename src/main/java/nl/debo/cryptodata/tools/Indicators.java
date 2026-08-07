package nl.debo.cryptodata.tools;

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

    public static List<Double> ema(
            List<Double> values,
            int period
    ) {

        var result = new ArrayList<Double>(
                Collections.nCopies(values.size(), Double.NaN)
        );

        // Find the first index with `period` consecutive valid values and
        // seed the EMA with their simple average.
        int start = -1;
        int run = 0;

        for (int i = 0; i < values.size(); i++) {
            if (Double.isNaN(values.get(i))) {
                run = 0;
            } else {
                run++;
                if (run == period) {
                    start = i;
                    break;
                }
            }
        }

        if (start < 0) {
            return result;
        }

        double sum = 0;

        for (int j = start - period + 1; j <= start; j++) {
            sum += values.get(j);
        }

        double ema = sum / period;

        result.set(start, ema);

        double multiplier = 2.0 / (period + 1);

        for (int i = start + 1; i < values.size(); i++) {

            double value = values.get(i);

            if (Double.isNaN(value)) {
                continue;
            }

            ema = ((value - ema) * multiplier) + ema;

            result.set(i, ema);
        }

        return result;
    }

    public static List<Double> macdLine(
            List<Double> prices,
            int fastPeriod,
            int slowPeriod
    ) {

        var fast = ema(prices, fastPeriod);
        var slow = ema(prices, slowPeriod);

        var result = new ArrayList<Double>(
                Collections.nCopies(prices.size(), Double.NaN)
        );

        for (int i = 0; i < prices.size(); i++) {

            double f = fast.get(i);
            double s = slow.get(i);

            if (!Double.isNaN(f) && !Double.isNaN(s)) {
                result.set(i, f - s);
            }
        }

        return result;
    }

    /**
     * Average True Range with Wilder smoothing, NaN-padded like the other
     * indicators. The true range at each index needs the previous close, so
     * values start at index {@code period}.
     */
    public static List<Double> atr(
            List<Double> highs,
            List<Double> lows,
            List<Double> closes,
            int period
    ) {

        var result = new ArrayList<Double>(
                Collections.nCopies(closes.size(), Double.NaN)
        );

        if (closes.size() <= period) {
            return result;
        }

        double trSum = 0;

        for (int i = 1; i <= period; i++) {
            trSum += trueRange(highs, lows, closes, i);
        }

        double atr = trSum / period;

        result.set(period, atr);

        for (int i = period + 1; i < closes.size(); i++) {

            double tr = trueRange(highs, lows, closes, i);

            atr = ((atr * (period - 1)) + tr) / period;

            result.set(i, atr);
        }

        return result;
    }

    private static double trueRange(
            List<Double> highs,
            List<Double> lows,
            List<Double> closes,
            int i
    ) {

        double previousClose = closes.get(i - 1);

        return Math.max(
                highs.get(i) - lows.get(i),
                Math.max(
                        Math.abs(highs.get(i) - previousClose),
                        Math.abs(lows.get(i) - previousClose)
                )
        );
    }

    /**
     * Bollinger %B: where the price sits inside the band of
     * {@code mean ± stdDevs * standard deviation} over the window
     * (0 = lower band, 1 = upper band). NaN-padded before the first full
     * window; a zero-width band yields 0.5.
     */
    public static List<Double> bollingerPercentB(
            List<Double> prices,
            int period,
            double stdDevs
    ) {

        var result = new ArrayList<Double>(
                Collections.nCopies(prices.size(), Double.NaN)
        );

        for (int i = period - 1; i < prices.size(); i++) {

            double sum = 0;

            for (int j = i - period + 1; j <= i; j++) {
                sum += prices.get(j);
            }

            double mean = sum / period;

            double squaredSum = 0;

            for (int j = i - period + 1; j <= i; j++) {
                double delta = prices.get(j) - mean;
                squaredSum += delta * delta;
            }

            double std = Math.sqrt(squaredSum / period);

            double band = 2 * stdDevs * std;

            if (band == 0) {
                result.set(i, 0.5);
            } else {
                double lower = mean - stdDevs * std;
                result.set(i, (prices.get(i) - lower) / band);
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
