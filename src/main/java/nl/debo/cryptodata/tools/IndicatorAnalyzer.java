package nl.debo.cryptodata.tools;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Computes RSI / Stochastic RSI (with K and D smoothing) and MACD over a
 * kline series and extracts the most recent row where every indicator has a
 * value.
 */
public final class IndicatorAnalyzer {

    private final int rsiPeriod;
    private final int stochRsiPeriod;
    private final int kPeriod;
    private final int dPeriod;
    private final int macdFastPeriod;
    private final int macdSlowPeriod;
    private final int macdSignalPeriod;
    private final int madrSmaPeriod;
    private final SignalNormalizer madrNormalizer;
    private final SignalNormalizer macdNormalizer;

    public IndicatorAnalyzer(int rsiPeriod, int stochRsiPeriod, int kPeriod, int dPeriod,
                             int macdFastPeriod, int macdSlowPeriod, int macdSignalPeriod,
                             int madrSmaPeriod, SignalNormalizer madrNormalizer,
                             SignalNormalizer macdNormalizer) {
        this.rsiPeriod = rsiPeriod;
        this.stochRsiPeriod = stochRsiPeriod;
        this.kPeriod = kPeriod;
        this.dPeriod = dPeriod;
        this.macdFastPeriod = macdFastPeriod;
        this.macdSlowPeriod = macdSlowPeriod;
        this.macdSignalPeriod = macdSignalPeriod;
        this.madrSmaPeriod = madrSmaPeriod;
        this.madrNormalizer = madrNormalizer;
        this.macdNormalizer = macdNormalizer;
    }

    /**
     * Returns the latest complete indicator row for the given klines, or empty
     * if there is not enough data. The last kline is assumed to be the
     * currently open candle and is ignored. The returned row has no news;
     * attach it with {@link ResultRow#withNews(String)}.
     */
    public Optional<ResultRow> latestRow(String symbol, String interval, List<Kline> klines) {
        if (klines.size() <= 1) {
            return Optional.empty();
        }

        // Remove the currently open candle.
        var closedKlines = klines.subList(0, klines.size() - 1);

        var closes = closedKlines.stream()
                .map(Kline::close)
                .toList();

        var rsi = Indicators.rsi(closes, rsiPeriod);
        var stochRsi = Indicators.stochasticRsi(rsi, stochRsiPeriod);
        var k = Indicators.sma(stochRsi, kPeriod);
        var d = Indicators.sma(k, dPeriod);
        var macd = Indicators.macdLine(closes, macdFastPeriod, macdSlowPeriod);
        var macdSignal = Indicators.ema(macd, macdSignalPeriod);
        var madr = madrNormalizer.normalize(Indicators.maDistanceRatio(closes, madrSmaPeriod));
        var macdStat = macdNormalizer.normalize(
                Indicators.scaledMacdHistogram(macd, macdSignal, closes));

        if (rsi.isEmpty() || stochRsi.isEmpty() || k.isEmpty() || d.isEmpty()) {
            return Optional.empty();
        }

        for (int i = closedKlines.size() - 1; i >= 0; i--) {
            if (i >= rsi.size() || i >= stochRsi.size() || i >= k.size() || i >= d.size()) {
                continue;
            }

            if (Double.isNaN(rsi.get(i))
                    || Double.isNaN(stochRsi.get(i))
                    || Double.isNaN(k.get(i))
                    || Double.isNaN(d.get(i))
                    || Double.isNaN(macd.get(i))
                    || Double.isNaN(macdSignal.get(i))) {
                continue;
            }

            var time = Instant.ofEpochMilli(closedKlines.get(i).closeTime());

            // The normalized stats need a long warmup (indicator window plus
            // normalizer window); on sparse series (e.g. 1M candles of a
            // young coin) they may still be NaN where everything else is
            // complete. Fall back to the neutral 0.5 instead of dropping the
            // whole row.
            double madrValue = Double.isNaN(madr.get(i)) ? 0.5 : madr.get(i);

            // MACD is a momentum signal: a positive histogram is bullish, so
            // flip the normalization to keep 0 = buy, 1 = sell.
            double macdStatValue = Double.isNaN(macdStat.get(i)) ? 0.5 : 1.0 - macdStat.get(i);

            return Optional.of(new ResultRow(
                    symbol,
                    interval,
                    time.toString(),
                    closedKlines.get(i).close(),
                    rsi.get(i),
                    stochRsi.get(i),
                    k.get(i),
                    d.get(i),
                    macd.get(i),
                    macdSignal.get(i),
                    macd.get(i) - macdSignal.get(i),
                    madrValue,
                    macdStatValue,
                    ""
            ));
        }

        return Optional.empty();
    }
}
