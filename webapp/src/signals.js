// Buy-signal rules per indicator. Each returns a boolean array aligned with the candles.

export const RSI_OVERSOLD = 30;     // default RSI trigger (0–100)
export const STOCH_OVERSOLD = 0.2;  // default Stoch RSI trigger (0–1)

/** Tunable trigger levels; the defaults above apply when a key is missing. */
export const DEFAULT_PARAMS = { rsiMax: RSI_OVERSOLD, stochMax: STOCH_OVERSOLD };

/** RSI: candle closes with RSI at or below the trigger line. */
export function rsiBuy({ rsi }, { rsiMax = RSI_OVERSOLD } = {}) {
  return rsi.map((v) => !Number.isNaN(v) && v <= rsiMax);
}

/** Stochastic RSI: %K crosses above %D while still at or below the trigger line. */
export function stochRsiBuy({ k, d }, { stochMax = STOCH_OVERSOLD } = {}) {
  return k.map((_, i) => {
    if (i === 0) return false;
    const k0 = k[i - 1], d0 = d[i - 1], k1 = k[i], d1 = d[i];
    if ([k0, d0, k1, d1].some(Number.isNaN)) return false;
    return k0 <= d0 && k1 > d1 && k1 <= stochMax;
  });
}

/** Bollinger Bands: candle closes at or below the lower band (20, 2σ). */
export function bollingerBuy({ closes, bbLower }) {
  return closes.map((c, i) => !Number.isNaN(bbLower[i]) && c <= bbLower[i]);
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

// `param` describes the slider for the indicator's trigger level: which key in the
// params object it sets, its range/step in the params' own unit, and how to show it.
export const INDICATORS = [
  {
    id: 'rsi', label: 'RSI', buy: rsiBuy,
    hint: (p) => `RSI(14) ≤ ${fmtRsi(p.rsiMax)}`,
    param: { key: 'rsiMax', min: 5, max: 95, step: 1, format: fmtRsi, label: 'RSI at or below' },
  },
  {
    id: 'srsi', label: 'S-RSI', buy: stochRsiBuy,
    hint: (p) => `%K crosses above %D, K ≤ ${fmtStoch(p.stochMax)}`,
    param: { key: 'stochMax', min: 0.05, max: 0.95, step: 0.01, format: fmtStoch, label: 'K at or below' },
  },
  { id: 'macd', label: 'MACD', buy: macdBuy, hint: () => 'MACD line crosses above signal' },
  { id: 'bb', label: 'BB', buy: bollingerBuy, hint: () => 'close at or below the lower Bollinger band (20, 2σ); bands are drawn on the price chart' },
];
function fmtRsi(v) { return `${Math.round(v)}`; }
function fmtStoch(v) { return `${Math.round(v * 100)}%`; }

/**
 * Combines the enabled indicators into one boolean per candle.
 * mode 'all': every enabled indicator must fire on that candle; 'any': at least one.
 * `params` holds the trigger levels (see DEFAULT_PARAMS).
 */
export function combineBuySignals(series, enabledIds, mode, params = DEFAULT_PARAMS) {
  const active = INDICATORS.filter((ind) => enabledIds.includes(ind.id));
  if (active.length === 0) return series.rsi.map(() => false);
  const arrays = active.map((ind) => ind.buy(series, params));
  return arrays[0].map((_, i) =>
    mode === 'any' ? arrays.some((a) => a[i]) : arrays.every((a) => a[i])
  );
}
