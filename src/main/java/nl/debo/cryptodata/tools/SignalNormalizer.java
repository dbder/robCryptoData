package nl.debo.cryptodata.tools;

import java.util.List;

/**
 * Normalizes an unbounded indicator series that oscillates around zero — an
 * MA distance ratio, a price-scaled MACD histogram, ... — into a 0..1
 * statistic with 0.5 at zero. The orientation is up to the caller: for a
 * mean-reversion signal like MADR a high value reads "sell"; for a momentum
 * signal like MACD the caller flips the result ({@code 1 - value}) so that
 * bullish stays on the buy (0) side.
 *
 * <p>Implementations follow the {@link Indicators} conventions: the output
 * list has the same size as the input and is NaN-padded wherever the input is
 * NaN or the strategy does not yet have enough history.
 */
public interface SignalNormalizer {

    List<Double> normalize(List<Double> values);

    /** Short name for logging and reports. */
    String name();
}
