// Simple long-only trade simulation on top of the buy signals.
//
// - Buy at the close of a candle with a buy signal, but only when no trade is open.
// - From the next candle on: sell when the low reaches entry × (1 − stopLoss),
//   or the high reaches entry × (1 + takeProfit). The stop is checked first
//   (conservative). Gaps beyond the level fill at the candle's open.
// - Signals that arrive while a trade is open are ignored (reported as `skipped`).

export const DEFAULT_STOP_LOSS = 0.08;    // sell if price drops 8% or more
export const DEFAULT_TAKE_PROFIT = 0.25;  // sell if price rises 25% or more
export const DEFAULT_STAKE_EUR = 1000;    // euros put into every trade

export function simulateTrades(candles, buy, { stopLoss = DEFAULT_STOP_LOSS, takeProfit = DEFAULT_TAKE_PROFIT, stake = DEFAULT_STAKE_EUR } = {}) {
  const trades = [];
  const skipped = [];
  let open = null;

  for (let i = 0; i < candles.length; i++) {
    const c = candles[i];

    if (open) {
      const stopPrice = open.entry * (1 - stopLoss);
      const targetPrice = open.entry * (1 + takeProfit);
      let exit = null;
      let reason = null;
      if (c.low <= stopPrice) {
        exit = Math.min(c.open, stopPrice);
        reason = 'stop';
      } else if (c.high >= targetPrice) {
        exit = Math.max(c.open, targetPrice);
        reason = 'target';
      }
      if (exit !== null) {
        const pct = exit / open.entry - 1;
        trades.push({ ...open, exitIndex: i, exit, reason, pct, eur: stake * pct });
        open = null;
      }
    }

    if (buy[i]) {
      if (open) skipped.push(i);
      else open = { entryIndex: i, entry: c.close };
    }
  }

  const closed = trades;
  const wins = closed.filter((t) => t.pct > 0).length;
  const compounded = closed.reduce((acc, t) => acc * (1 + t.pct), 1) - 1;
  const eur = closed.reduce((sum, t) => sum + t.eur, 0);   // realized P/L with `stake` per trade
  let openTrade = null;
  if (open) {
    const last = candles[candles.length - 1];
    const pct = last.close / open.entry - 1;
    openTrade = {
      ...open,
      stopPrice: open.entry * (1 - stopLoss),
      targetPrice: open.entry * (1 + takeProfit),
      pct,
      eur: stake * pct,   // unrealized
    };
  }

  return { trades: closed, skipped, open: openTrade, wins, losses: closed.length - wins, compounded, eur, stake };
}
