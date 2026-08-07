package nl.debo.cryptodata.predict;

import nl.debo.cryptodata.tools.Kline;

import java.util.ArrayList;
import java.util.List;

/**
 * A chronological feature matrix with one row per usable candle: features
 * as columns, the label the model should predict for that candle, and the
 * raw next-candle return (fraction, 0.10 = +10%) used by the trading
 * simulation.
 */
public record Dataset(List<String> featureNames, double[][] rows, int[] labels, double[] nextReturns) {

    /**
     * Builds a dataset from klines: one row per candle that has a defined
     * label and no NaN features (indicator warm-up rows drop out). Rows keep
     * the klines' chronological order.
     */
    public static Dataset build(List<Kline> klines, FeatureExtractor extractor, Labeler labeler) {
        double[][] columns = extractor.extract(klines);
        int[] allLabels = labeler.labels(klines);

        var keptRows = new ArrayList<double[]>();
        var keptLabels = new ArrayList<Integer>();
        var keptReturns = new ArrayList<Double>();
        for (int i = 0; i < klines.size(); i++) {
            if (allLabels[i] == Labeler.UNDEFINED || klines.get(i).close() == 0) {
                continue;
            }
            double[] row = new double[columns.length];
            boolean valid = true;
            for (int f = 0; f < columns.length; f++) {
                row[f] = columns[f][i];
                if (Double.isNaN(row[f])) {
                    valid = false;
                    break;
                }
            }
            if (!valid) {
                continue;
            }
            keptRows.add(row);
            keptLabels.add(allLabels[i]);
            keptReturns.add(i + 1 < klines.size()
                    ? klines.get(i + 1).close() / klines.get(i).close() - 1
                    : 0.0);
        }

        return new Dataset(
                extractor.featureNames(),
                keptRows.toArray(new double[0][]),
                keptLabels.stream().mapToInt(Integer::intValue).toArray(),
                keptReturns.stream().mapToDouble(Double::doubleValue).toArray()
        );
    }

    public int size() {
        return rows.length;
    }

    /** Index of the named feature within each row. */
    public int featureIndex(String name) {
        int index = featureNames.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("Unknown feature: " + name);
        }
        return index;
    }
}
