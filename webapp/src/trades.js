// Simple long-only trade simulation on top of the buy signals.
//
// - Buy at the close of a candle with a buy signal, but only when no trade is open.
// - Every later candle is judged at its close: when the close is at or below the
//   trade's stop price, or at or above its target price, the trade is sold at that
//   close. So a candle that closes −10% books −10%, one that closes +30% books
//   +30% — not the threshold.
// - The stop and target are fixed at entry, per `exitMode`:
//   'pct' — entry × (1 ∓ stopLoss/takeProfit);
//   'atr' — entry ∓ stopAtr/targetAtr × ATR(14) at the entry candle, so exits are
//   wide on volatile coins and tight on calm ones. A signal during the ATR warmup
//   (first 14 candles) cannot be sized and is ignored.
// - A fee (default 0.25%) is paid on the buy and again on the sell.
// - With `skipWhileOpen` (default) signals that arrive while a trade is open are
//   ignored (reported as `skipped`); without it every signal opens its own trade,
//   each with its own stop/target.
// - `from` / `to` (ms timestamps, optional) restrict trading to candles opening in
//   that window: buys and sells only happen inside it. Candles before `from` are
//   still there for the indicators to warm up on, candles after `to` are ignored,
//   and trades still open at `to` are valued at the last candle inside the window.

import { atr } from './indicators.js';

export const DEFAULT_STOP_LOSS = 0.08;    // sell if the candle closes 8% or more below entry
export const DEFAULT_TAKE_PROFIT = 0.25;  // sell if the candle closes 25% or more above entry
export const DEFAULT_STOP_ATR = 2;        // 'atr' mode: stop this many ATRs below entry
export const DEFAULT_TARGET_ATR = 4;      // 'atr' mode: target this many ATRs above entry
export const ATR_PERIOD = 14;
export const DEFAULT_STAKE_EUR = 100;    // euros put into every trade
export const DEFAULT_FEE = 0.0025;        // 0.25% per buy and per sell

/** Euro result of a trade: `stake` in, fee on the buy, fee on the sell. */
export function tradeResultEur(stake, entry, exit, fee) {
  const bought = stake * (1 - fee);            // euros actually invested after the buy fee
  const proceeds = bought * (exit / entry) * (1 - fee);
  return proceeds - stake;
}

export function simulateTrades(candles, buy, {
  exitMode = 'pct',
  stopLoss = DEFAULT_STOP_LOSS,
  takeProfit = DEFAULT_TAKE_PROFIT,
  stopAtr = DEFAULT_STOP_ATR,
  targetAtr = DEFAULT_TARGET_ATR,
  stake = DEFAULT_STAKE_EUR,
  fee = DEFAULT_FEE,
  skipWhileOpen = true,
  from = null,
  to = null,
} = {}) {
  const trades = [];
  const skipped = [];
  let opens = [];   // open trades, oldest first
  const { firstIndex, lastIndex } = tradingWindow(candles, from, to);

  const atrSeries = exitMode === 'atr'
    ? atr(candles.map((c) => c.high), candles.map((c) => c.low), candles.map((c) => c.close), ATR_PERIOD)
    : null;
  /** Stop/target prices for a trade entered at candle `i`; null when the ATR is still warming up. */
  const exitPrices = (entry, i) => {
    if (!atrSeries) return { stopPrice: entry * (1 - stopLoss), targetPrice: entry * (1 + takeProfit) };
    const a = atrSeries[i];
    if (Number.isNaN(a)) return null;
    return { stopPrice: entry - stopAtr * a, targetPrice: entry + targetAtr * a };
  };

  for (let i = firstIndex; i <= lastIndex; i++) {
    const c = candles[i];

    opens = opens.filter((open) => {
      const reason = c.close <= open.stopPrice ? 'stop' : c.close >= open.targetPrice ? 'target' : null;
      if (!reason) return true;
      const move = c.close / open.entry - 1;
      const eur = tradeResultEur(stake, open.entry, c.close, fee);
      trades.push({ ...open, exitIndex: i, exit: c.close, reason, move, eur, pct: eur / stake });
      return false;
    });

    if (buy[i]) {
      const exits = exitPrices(c.close, i);
      if (!exits) continue;
      if (skipWhileOpen && opens.length > 0) skipped.push(i);
      else opens.push({ entryIndex: i, entry: c.close, ...exits });
    }
  }
  trades.sort((a, b) => a.entryIndex - b.entryIndex || a.exitIndex - b.exitIndex);

  const wins = trades.filter((t) => t.eur > 0).length;
  const eur = trades.reduce((sum, t) => sum + t.eur, 0);   // realized P/L, `stake` per trade, fees included
  const compounded = trades.reduce((acc, t) => acc * (1 + t.pct), 1) - 1;
  const last = candles[lastIndex];
  const openTrades = opens.map((open) => {
    const unrealized = tradeResultEur(stake, open.entry, last.close, fee);
    return {
      ...open,
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
    firstIndex, lastIndex,         // candle indices the simulation actually traded on
  };
}

/**
 * Index range of the candles that overlap [from, to]; empty range when none do.
 * A candle counts as soon as any part of it lies in the range, so a range that starts
 * mid-week or mid-month still includes the weekly/monthly candle it starts in: '1y' on
 * the monthly timeframe is 12 candles, not 11.
 */
export function tradingWindow(candles, from, to) {
  let firstIndex = 0;
  let lastIndex = candles.length - 1;
  if (from != null) while (firstIndex < candles.length && candles[firstIndex].closeTime < from) firstIndex++;
  if (to != null) while (lastIndex >= 0 && candles[lastIndex].openTime > to) lastIndex--;
  return { firstIndex, lastIndex };
}
