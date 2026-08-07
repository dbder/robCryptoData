package nl.debo.cryptodata.predict;

import nl.debo.cryptodata.tools.Kline;

import java.util.List;

/**
 * Labels each candle with whether the <em>next</em> candle closes higher
 * ({@code 1}) or not ({@code 0}); a flat close counts as "not up". The last
 * candle has no next candle and gets {@link #UNDEFINED}.
 */
public final class NextDayDirectionLabeler implements Labeler {

    @Override
    public int[] labels(List<Kline> klines) {
        int[] labels = new int[klines.size()];
        for (int i = 0; i < klines.size() - 1; i++) {
            labels[i] = klines.get(i + 1).close() > klines.get(i).close() ? 1 : 0;
        }
        if (labels.length > 0) {
            labels[labels.length - 1] = UNDEFINED;
        }
        return labels;
    }
}
