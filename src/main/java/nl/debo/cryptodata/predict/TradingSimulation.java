package nl.debo.cryptodata.predict;

/**
 * Simulates a simple long-only strategy over walk-forward predictions: hold
 * the coin on days the model predicts "up", hold cash otherwise. Every
 * position change (a "swap" between cash and coin) is one trade costing
 * {@code feePerTrade} of the current equity, Bitvavo-style; the position is
 * flattened at the end so the result is comparable with buy-and-hold, which
 * pays the same entry and exit fees.
 */
public final class TradingSimulation {

    /** All returns are fractions of starting equity: 0.10 = +10%. */
    public record TradingResult(double netReturn, double buyHoldReturn, int trades, double feesPaid) {
    }

    private TradingSimulation() {
    }

    /**
     * @param nextReturns   per-row next-candle returns from {@link Dataset#nextReturns()}
     * @param probabilities per-prediction up-probabilities from
     *                      {@link WalkForwardBacktest.ModelResult#probabilities()}
     * @param startRow      dataset row of the first prediction (the backtest's minTrainSize)
     * @param feePerTrade   fee fraction charged on every position change, e.g. 0.0025
     */
    public static TradingResult simulate(double[] nextReturns, double[] probabilities,
                                         int startRow, double feePerTrade) {
        double equity = 1.0;
        double feesPaid = 0;
        int trades = 0;
        boolean invested = false;

        for (int i = 0; i < probabilities.length; i++) {
            boolean wantIn = probabilities[i] >= 0.5;
            if (wantIn != invested) {
                feesPaid += equity * feePerTrade;
                equity *= 1 - feePerTrade;
                trades++;
                invested = wantIn;
            }
            if (invested) {
                equity *= 1 + nextReturns[startRow + i];
            }
        }
        if (invested) {
            feesPaid += equity * feePerTrade;
            equity *= 1 - feePerTrade;
            trades++;
        }

        double buyHold = 1 - feePerTrade;
        for (int i = 0; i < probabilities.length; i++) {
            buyHold *= 1 + nextReturns[startRow + i];
        }
        buyHold *= 1 - feePerTrade;

        return new TradingResult(equity - 1, buyHold - 1, trades, feesPaid);
    }
}
