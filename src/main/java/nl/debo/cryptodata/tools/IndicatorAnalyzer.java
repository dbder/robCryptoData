package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.DutchDate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Computes RSI / Stochastic RSI (with K and D smoothing) and MACD over a
 * kline series and extracts the most recent row where every selected
 * indicator has a value.
 *
 * <p>The {@link Indicator} selection decides what ends up in the row: an
 * indicator outside it is reported as {@link Double#NaN} and does not have
 * to be complete for a row to count. The series themselves are always
 * computed — they are cheap, and K and D are built on the Stochastic RSI
 * anyway, so selecting only K still needs the whole chain.</p>
 */
public final class IndicatorAnalyzer {

    private final Set<Indicator> indicators;
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

    /**
     * @param indicators the indicators to report on; the rest come out as NaN
     */
    public IndicatorAnalyzer(Set<Indicator> indicators,
                             int rsiPeriod, int stochRsiPeriod, int kPeriod, int dPeriod,
                             int macdFastPeriod, int macdSlowPeriod, int macdSignalPeriod,
                             int madrSmaPeriod, SignalNormalizer madrNormalizer,
                             SignalNormalizer macdNormalizer) {
        if (indicators.isEmpty()) {
            throw new IllegalArgumentException("Select at least one indicator");
        }
        this.indicators = Set.copyOf(indicators);
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
     * Returns the latest row where every selected indicator is complete for
     * the given klines, or empty if there is not enough data. Candles that
     * have not closed yet are filtered out here, so an uncompleted candle is
     * never used — whether the source ends the series with one (the live API
     * usually does) or not (a market without trades in the open candle, a
     * store of closed candles).
     */
    public Optional<ResultRow> latestRow(String symbol, String interval, List<Kline> klines) {
        long now = System.currentTimeMillis();
        var closedKlines = klines.stream()
                .filter(k -> k.closeTime() <= now)
                .toList();

        if (closedKlines.isEmpty()) {
            return Optional.empty();
        }

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

        boolean wantRsi = indicators.contains(Indicator.RSI);
        boolean wantStochRsi = indicators.contains(Indicator.STOCH_RSI);
        boolean wantK = indicators.contains(Indicator.K);
        boolean wantD = indicators.contains(Indicator.D);
        boolean wantMacd = indicators.contains(Indicator.MACD);
        boolean wantMadr = indicators.contains(Indicator.MADR);

        for (int i = closedKlines.size() - 1; i >= 0; i--) {
            // Every series is NaN-padded to the length of the closes; the
            // size guards only protect against a shorter series.
            if ((wantRsi && missing(rsi, i))
                    || (wantStochRsi && missing(stochRsi, i))
                    || (wantK && missing(k, i))
                    || (wantD && missing(d, i))
                    || (wantMacd && (missing(macd, i) || missing(macdSignal, i)))) {
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
                    DutchDate.compact(closedKlines.get(i).openTime()),
                    time.toString(),
                    closedKlines.get(i).close(),
                    wantRsi ? rsi.get(i) : Double.NaN,
                    wantStochRsi ? stochRsi.get(i) : Double.NaN,
                    wantK ? k.get(i) : Double.NaN,
                    wantD ? d.get(i) : Double.NaN,
                    wantMacd ? macd.get(i) : Double.NaN,
                    wantMacd ? macdSignal.get(i) : Double.NaN,
                    wantMacd ? macd.get(i) - macdSignal.get(i) : Double.NaN,
                    wantMadr ? madrValue : Double.NaN,
                    wantMacd ? macdStatValue : Double.NaN
            ));
        }

        return Optional.empty();
    }

    /** True when {@code series} has no value at {@code i} (too short or NaN warmup). */
    private static boolean missing(List<Double> series, int i) {
        return i >= series.size() || Double.isNaN(series.get(i));
    }
}
