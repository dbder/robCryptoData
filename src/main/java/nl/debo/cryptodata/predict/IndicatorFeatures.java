package nl.debo.cryptodata.predict;

import nl.debo.cryptodata.tools.Indicators;
import nl.debo.cryptodata.tools.Kline;

import java.util.Arrays;
import java.util.List;

/**
 * Default feature set: the indicators the project already computes (RSI,
 * smoothed Stochastic RSI K/D, MACD histogram scaled by price) plus recent
 * returns and volume change. Periods match the {@code CryptoAnalysis}
 * conventions (14/14/3/3/12/26/9).
 */
public final class IndicatorFeatures implements FeatureExtractor {

    private static final int RSI_PERIOD = 14;
    private static final int STOCH_RSI_PERIOD = 14;
    private static final int K_PERIOD = 3;
    private static final int D_PERIOD = 3;
    private static final int MACD_FAST_PERIOD = 12;
    private static final int MACD_SLOW_PERIOD = 26;
    private static final int MACD_SIGNAL_PERIOD = 9;

    private static final List<String> NAMES = List.of(
            "rsi14", "stochK", "stochD", "macdHist", "ret1", "ret3", "ret7", "volChg");

    @Override
    public List<String> featureNames() {
        return NAMES;
    }

    @Override
    public double[][] extract(List<Kline> klines) {
        int n = klines.size();
        var closes = klines.stream().map(Kline::close).toList();

        var rsi = Indicators.rsi(closes, RSI_PERIOD);
        var stochRsi = Indicators.stochasticRsi(rsi, STOCH_RSI_PERIOD);
        var k = Indicators.sma(stochRsi, K_PERIOD);
        var d = Indicators.sma(k, D_PERIOD);
        var macd = Indicators.macdLine(closes, MACD_FAST_PERIOD, MACD_SLOW_PERIOD);
        var signal = Indicators.ema(macd, MACD_SIGNAL_PERIOD);

        double[][] features = new double[NAMES.size()][n];
        for (double[] column : features) {
            Arrays.fill(column, Double.NaN);
        }

        for (int i = 0; i < n; i++) {
            features[0][i] = at(rsi, i);
            features[1][i] = at(k, i);
            features[2][i] = at(d, i);

            double close = closes.get(i);
            double m = at(macd, i);
            double s = at(signal, i);
            if (!Double.isNaN(m) && !Double.isNaN(s) && close != 0) {
                features[3][i] = (m - s) / close;
            }

            features[4][i] = returnOver(closes, i, 1);
            features[5][i] = returnOver(closes, i, 3);
            features[6][i] = returnOver(closes, i, 7);

            if (i > 0) {
                double previous = klines.get(i - 1).volume();
                double current = klines.get(i).volume();
                if (previous > 0 && current > 0) {
                    features[7][i] = Math.log(current / previous);
                }
            }
        }
        return features;
    }

    private static double at(List<Double> series, int i) {
        return i < series.size() ? series.get(i) : Double.NaN;
    }

    private static double returnOver(List<Double> closes, int i, int lag) {
        if (i < lag) {
            return Double.NaN;
        }
        double past = closes.get(i - lag);
        return past != 0 ? closes.get(i) / past - 1 : Double.NaN;
    }
}
