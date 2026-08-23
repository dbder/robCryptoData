// Straight ports of nl.debo.cryptodata.tools.Indicators (Java). Every function
// returns an array of the same length as its input, NaN-padded during warmup.

const nanArray = (n) => new Array(n).fill(NaN);

/** RSI, Wilder smoothing (see rsi.md). First value at index `period`. */
export function rsi(prices, period = 14) {
  const result = nanArray(prices.length);
  if (prices.length <= period) return result;

  let gainSum = 0;
  let lossSum = 0;
  for (let i = 1; i <= period; i++) {
    const change = prices[i] - prices[i - 1];
    if (change > 0) gainSum += change;
    else lossSum -= change;
  }
  let avgGain = gainSum / period;
  let avgLoss = lossSum / period;
  result[period] = toRsi(avgGain, avgLoss);

  for (let i = period + 1; i < prices.length; i++) {
    const change = prices[i] - prices[i - 1];
    avgGain = (avgGain * (period - 1) + Math.max(change, 0)) / period;
    avgLoss = (avgLoss * (period - 1) + Math.max(-change, 0)) / period;
    result[i] = toRsi(avgGain, avgLoss);
  }
  return result;
}

function toRsi(avgGain, avgLoss) {
  if (avgLoss === 0) return 100;
  if (avgGain === 0) return 0;
  return 100 - 100 / (1 + avgGain / avgLoss);
}

/** Position of RSI inside its own min-max window, 0..1 (see stochastic-rsi.md). */
export function stochasticRsi(rsiSeries, period = 14) {
  const result = nanArray(rsiSeries.length);
  for (let i = period - 1; i < rsiSeries.length; i++) {
    const current = rsiSeries[i];
    if (Number.isNaN(current)) continue;
    let lowest = Infinity;
    let highest = -Infinity;
    let valid = true;
    for (let j = i - period + 1; j <= i; j++) {
      const v = rsiSeries[j];
      if (Number.isNaN(v)) { valid = false; break; }
      lowest = Math.min(lowest, v);
      highest = Math.max(highest, v);
    }
    if (!valid) continue;
    const range = highest - lowest;
    result[i] = range === 0 ? 0 : (current - lowest) / range;
  }
  return result;
}

/** NaN-aware simple moving average: only set when the whole window is valid. */
export function sma(values, period) {
  const result = nanArray(values.length);
  for (let i = period - 1; i < values.length; i++) {
    let sum = 0;
    let valid = true;
    for (let j = i - period + 1; j <= i; j++) {
      const v = values[j];
      if (Number.isNaN(v)) { valid = false; break; }
      sum += v;
    }
    if (valid) result[i] = sum / period;
  }
  return result;
}

/** EMA seeded on the first run of `period` consecutive valid values; NaN inputs are skipped. */
export function ema(values, period) {
  const result = nanArray(values.length);
  let start = -1;
  let run = 0;
  for (let i = 0; i < values.length; i++) {
    if (Number.isNaN(values[i])) run = 0;
    else if (++run === period) { start = i; break; }
  }
  if (start < 0) return result;

  let sum = 0;
  for (let j = start - period + 1; j <= start; j++) sum += values[j];
  let e = sum / period;
  result[start] = e;

  const k = 2 / (period + 1);
  for (let i = start + 1; i < values.length; i++) {
    const v = values[i];
    if (Number.isNaN(v)) continue;
    e = (v - e) * k + e;
    result[i] = e;
  }
  return result;
}

/** Average True Range, Wilder smoothing (port of the Java atr). First value at index `period`. */
export function atr(highs, lows, closes, period = 14) {
  const result = nanArray(closes.length);
  if (closes.length <= period) return result;
  const tr = (i) => Math.max(
    highs[i] - lows[i],
    Math.abs(highs[i] - closes[i - 1]),
    Math.abs(lows[i] - closes[i - 1]),
  );
  let sum = 0;
  for (let i = 1; i <= period; i++) sum += tr(i);
  let a = sum / period;
  result[period] = a;
  for (let i = period + 1; i < closes.length; i++) {
    a = (a * (period - 1) + tr(i)) / period;
    result[i] = a;
  }
  return result;
}

/** Bollinger Bands: SMA(period) ± mult population standard deviations. */
export function bollingerBands(prices, period = 20, mult = 2) {
  const middle = sma(prices, period);
  const upper = nanArray(prices.length);
  const lower = nanArray(prices.length);
  for (let i = 0; i < prices.length; i++) {
    if (Number.isNaN(middle[i])) continue;
    let sq = 0;
    for (let j = i - period + 1; j <= i; j++) sq += (prices[j] - middle[i]) ** 2;
    const sd = Math.sqrt(sq / period);
    upper[i] = middle[i] + mult * sd;
    lower[i] = middle[i] - mult * sd;
  }
  return { middle, upper, lower };
}

/** MACD line = EMA(fast) − EMA(slow); NaN unless both are valid. */
export function macdLine(prices, fast = 12, slow = 26) {
  const f = ema(prices, fast);
  const s = ema(prices, slow);
  return prices.map((_, i) =>
    Number.isNaN(f[i]) || Number.isNaN(s[i]) ? NaN : f[i] - s[i]
  );
}

/** Everything the chart needs, computed once per candle series. Same parameters as CryptoAnalysis. */
export function computeAll(candles) {
  const closes = candles.map((c) => c.close);
  const rsiS = rsi(closes, 14);
  const stoch = stochasticRsi(rsiS, 14);
  const k = sma(stoch, 3);
  const d = sma(k, 3);
  const macd = macdLine(closes, 12, 26);
  const signal = ema(macd, 9);
  const hist = macd.map((m, i) =>
    Number.isNaN(m) || Number.isNaN(signal[i]) ? NaN : m - signal[i]
  );
  const bb = bollingerBands(closes, 20, 2);
  return { closes, rsi: rsiS, stochRsi: stoch, k, d, macd, signal, hist, bbUpper: bb.upper, bbMiddle: bb.middle, bbLower: bb.lower };
}
