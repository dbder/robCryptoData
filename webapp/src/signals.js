// Buy-signal rules per indicator. Each returns a boolean array aligned with the candles.

export const RSI_OVERSOLD = 30;
export const STOCH_OVERSOLD = 0.2;

/** RSI: candle closes with RSI at or below the oversold line. */
export function rsiBuy({ rsi }) {
  return rsi.map((v) => !Number.isNaN(v) && v <= RSI_OVERSOLD);
}

/** Stochastic RSI: %K crosses above %D while both are in the oversold zone. */
export function stochRsiBuy({ k, d }) {
  return k.map((_, i) => {
    if (i === 0) return false;
    const k0 = k[i - 1], d0 = d[i - 1], k1 = k[i], d1 = d[i];
    if ([k0, d0, k1, d1].some(Number.isNaN)) return false;
    return k0 <= d0 && k1 > d1 && k1 <= STOCH_OVERSOLD;
  });
}

/** MACD: bullish crossover — the histogram flips from ≤ 0 to > 0. */
export function macdBuy({ hist }) {
  return hist.map((h, i) => {
    if (i === 0) return false;
    const prev = hist[i - 1];
    if (Number.isNaN(prev) || Number.isNaN(h)) return false;
    return prev <= 0 && h > 0;
  });
}

export const INDICATORS = [
  { id: 'rsi', label: 'RSI', hint: `RSI(14) ≤ ${RSI_OVERSOLD}`, buy: rsiBuy },
  { id: 'srsi', label: 'S-RSI', hint: `%K crosses above %D, K ≤ ${STOCH_OVERSOLD}`, buy: stochRsiBuy },
  { id: 'macd', label: 'MACD', hint: 'MACD line crosses above signal', buy: macdBuy },
];

/**
 * Combines the enabled indicators into one boolean per candle.
 * mode 'all': every enabled indicator must fire on that candle; 'any': at least one.
 */
export function combineBuySignals(series, enabledIds, mode) {
  const active = INDICATORS.filter((ind) => enabledIds.includes(ind.id));
  if (active.length === 0) return series.rsi.map(() => false);
  const arrays = active.map((ind) => ind.buy(series));
  return arrays[0].map((_, i) =>
    mode === 'any' ? arrays.some((a) => a[i]) : arrays.every((a) => a[i])
  );
}
