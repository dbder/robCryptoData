package nl.debo.cryptodata.predict;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;

/**
 * Defines the prediction target: what the model should predict for each
 * candle. Swapping the target (e.g. direction over a week instead of a day)
 * means swapping the Labeler; nothing else changes.
 */
public interface Labeler {

    int UNDEFINED = -1;

    /**
     * One label per kline: {@code 1} = up, {@code 0} = not up,
     * {@link #UNDEFINED} where no label exists (e.g. the last candle has no
     * "next day" yet).
     */
    int[] labels(List<Kline> klines);
}
