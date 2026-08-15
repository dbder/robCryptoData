// Simple long-only trade simulation on top of the buy signals.
//
// - Buy at the close of a candle with a buy signal, but only when no trade is open.
// - Every later candle is judged at its close: when the close is at or below
//   entry × (1 − stopLoss), or at or above entry × (1 + takeProfit), the trade is
//   sold at that close. So a candle that closes −10% books −10%, one that closes
//   +30% books +30% — not the threshold.
// - A fee (default 0.25%) is paid on the buy and again on the sell.
// - With `skipWhileOpen` (default) signals that arrive while a trade is open are
//   ignored (reported as `skipped`); without it every signal opens its own trade,
//   each with its own stop/target.

export const DEFAULT_STOP_LOSS = 0.08;    // sell if the candle closes 8% or more below entry
export const DEFAULT_TAKE_PROFIT = 0.25;  // sell if the candle closes 25% or more above entry
export const DEFAULT_STAKE_EUR = 1000;    // euros put into every trade
export const DEFAULT_FEE = 0.0025;        // 0.25% per buy and per sell

/** Euro result of a trade: `stake` in, fee on the buy, fee on the sell. */
export function tradeResultEur(stake, entry, exit, fee) {
  const bought = stake * (1 - fee);            // euros actually invested after the buy fee
  const proceeds = bought * (exit / entry) * (1 - fee);
  return proceeds - stake;
}

export function simulateTrades(candles, buy, {
  stopLoss = DEFAULT_STOP_LOSS,
  takeProfit = DEFAULT_TAKE_PROFIT,
  stake = DEFAULT_STAKE_EUR,
  fee = DEFAULT_FEE,
  skipWhileOpen = true,
} = {}) {
  const trades = [];
  const skipped = [];
  let opens = [];   // open trades, oldest first

  for (let i = 0; i < candles.length; i++) {
    const c = candles[i];

    opens = opens.filter((open) => {
      const move = c.close / open.entry - 1;
      const reason = move <= -stopLoss ? 'stop' : move >= takeProfit ? 'target' : null;
      if (!reason) return true;
      const eur = tradeResultEur(stake, open.entry, c.close, fee);
      trades.push({ ...open, exitIndex: i, exit: c.close, reason, move, eur, pct: eur / stake });
      return false;
    });

    if (buy[i]) {
      if (skipWhileOpen && opens.length > 0) skipped.push(i);
      else opens.push({ entryIndex: i, entry: c.close });
    }
  }
  trades.sort((a, b) => a.entryIndex - b.entryIndex || a.exitIndex - b.exitIndex);

  const wins = trades.filter((t) => t.eur > 0).length;
  const eur = trades.reduce((sum, t) => sum + t.eur, 0);   // realized P/L, `stake` per trade, fees included
  const compounded = trades.reduce((acc, t) => acc * (1 + t.pct), 1) - 1;
  const last = candles[candles.length - 1];
  const openTrades = opens.map((open) => {
    const unrealized = tradeResultEur(stake, open.entry, last.close, fee);
    return {
      ...open,
      stopPrice: open.entry * (1 - stopLoss),
      targetPrice: open.entry * (1 + takeProfit),
      move: last.close / open.entry - 1,
      eur: unrealized,            // as if sold at the last close, fees included
      pct: unrealized / stake,
    };
  });
  const openEur = openTrades.reduce((sum, t) => sum + t.eur, 0);

  return {
    trades, skipped, openTrades, openEur,
    open: openTrades[0] ?? null,   // oldest open trade (convenience for single-position mode)
    wins, losses: trades.length - wins, compounded, eur, stake, fee,
  };
}
