import { useEffect, useMemo, useRef } from 'react';
import {
  createChart,
  CandlestickSeries,
  LineSeries,
  HistogramSeries,
  ColorType,
  CrosshairMode,
  createSeriesMarkers,
} from 'lightweight-charts';
import { DEFAULT_PARAMS } from './signals.js';

export const COLORS = {
  up: '#26a69a',
  down: '#ef5350',
  gold: '#f5b301',
  goldBorder: '#ffd75e',
  bg: '#0f1419',
  grid: '#1c2430',
  text: '#aab4c3',
  line1: '#4fa3ff',
  line2: '#ff8a65',
  hist: '#607d8b',
  level: '#3b4a5c',
  ownBuy: '#4fa3ff',    // the user's real buys / sells (ledger), distinct from the gold/green/red simulation
  ownSell: '#c77dff',
  pending: '#8b98aa',   // synthetic candle for a trade newer than the candle store (no closed candle yet)
};

const toTime = (ms) => Math.floor(ms / 1000);

function pointsOf(candles, values) {
  const out = [];
  for (let i = 0; i < candles.length; i++) {
    if (!Number.isNaN(values[i])) out.push({ time: toTime(candles[i].openTime), value: values[i] });
  }
  return out;
}

/** Index of the candle a moment (ms) falls in: the last candle opening at or before it, -1 when before the first. */
function candleIndexAt(candles, ms) {
  let lo = 0, hi = candles.length - 1, found = -1;
  while (lo <= hi) {
    const mid = (lo + hi) >> 1;
    if (candles[mid].openTime <= ms) { found = mid; lo = mid + 1; } else hi = mid - 1;
  }
  return found;
}

const eurText = (v) => `€${Math.round(v).toLocaleString('en-US')}`;

/**
 * Markers for the user's real trades: one per candle and side (several fills in the same
 * candle are summed, e.g. "3 buys €2,990"), buys below the bar, sells above.
 * Trades outside the given candles (before the first, after the last closes) are dropped.
 */
export function ownTradeMarkers(candles, ownTrades) {
  const perCandle = new Map();   // `${index}:${side}` → { index, side, n, eur }
  for (const t of ownTrades) {
    const i = candleIndexAt(candles, t.time);
    if (i < 0 || t.time > candles[i].closeTime) continue;
    const key = `${i}:${t.side}`;
    const agg = perCandle.get(key) ?? { index: i, side: t.side, n: 0, eur: 0 };
    agg.n++;
    agg.eur += t.amount * t.price;
    perCandle.set(key, agg);
  }
  return [...perCandle.values()].map(({ index, side, n, eur }) => {
    const isBuy = side === 'buy';
    const label = n === 1 ? side : `${n} ${side}s`;
    return {
      time: toTime(candles[index].openTime),
      position: isBuy ? 'belowBar' : 'aboveBar',
      shape: isBuy ? 'arrowUp' : 'arrowDown',
      color: isBuy ? COLORS.ownBuy : COLORS.ownSell,
      text: `${label} ${eurText(eur)}`,
    };
  });
}

const HOUR = 3600 * 1000;
const INTERVAL_MS = { '1h': HOUR, '2h': 2 * HOUR, '4h': 4 * HOUR, '6h': 6 * HOUR, '8h': 8 * HOUR, '12h': 12 * HOUR, '1d': 24 * HOUR, '1W': 7 * 24 * HOUR };
const MAX_PENDING_SLOTS = 500;   // how far past the store the pending bars may reach (a very stale store on 1h)

/** Open time (ms) of the candle after the one opening at `openTime`; `fallbackMs` when the interval is unknown. */
function nextOpen(openTime, interval, fallbackMs) {
  if (interval === '1M') {
    const d = new Date(openTime);
    return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1);
  }
  return openTime + (INTERVAL_MS[interval] ?? fallbackMs);
}

/**
 * Bars for the time after the last stored candle, so a trade newer than the store still gets its
 * own spot on the time axis: the store only holds closed candles, so today's trade has no candle yet
 * (and neither has one from yesterday until the store is refreshed). A slot with trades becomes a
 * synthetic "pending" candle built from the trade prices themselves; empty slots in between are
 * whitespace (they only extend the axis). Nothing when no trade is newer than the store.
 */
export function pendingBars(candles, ownTrades, interval) {
  const last = candles.at(-1);
  if (!last) return [];
  const newer = ownTrades.filter((t) => t.time > last.closeTime).sort((a, b) => a.time - b.time);
  if (!newer.length) return [];
  const dur = last.closeTime - last.openTime + 1;
  const bars = [];
  let openTime = last.closeTime + 1;
  for (let k = 0; k < newer.length && bars.length < MAX_PENDING_SLOTS;) {
    const closeTime = nextOpen(openTime, interval, dur) - 1;
    const prices = [];
    while (k < newer.length && newer[k].time <= closeTime) prices.push(newer[k++].price);
    bars.push(prices.length
      ? { openTime, closeTime, open: prices[0], high: Math.max(...prices), low: Math.min(...prices), close: prices.at(-1), pending: true }
      : { openTime, closeTime, whitespace: true });
    openTime = closeTime + 1;
  }
  return bars;
}

const PANES = {
  rsi: {
    title: 'RSI (14)',
    build(chart, pane, candles, s, p) {
      const line = chart.addSeries(LineSeries,
        { color: COLORS.line1, lineWidth: 2, priceLineVisible: false, lastValueVisible: true, title: 'RSI' }, pane);
      line.setData(pointsOf(candles, s.rsi));
      line.createPriceLine({ price: p.rsiMax, color: COLORS.gold, lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: 'buy' });
      line.createPriceLine({ price: 70, color: COLORS.level, lineWidth: 1, lineStyle: 2, axisLabelVisible: false });
      return [line];
    },
  },
  srsi: {
    title: 'Stoch RSI (14,3,3)',
    build(chart, pane, candles, s, p) {
      const k = chart.addSeries(LineSeries,
        { color: COLORS.line1, lineWidth: 2, priceLineVisible: false, title: 'K' }, pane);
      const d = chart.addSeries(LineSeries,
        { color: COLORS.line2, lineWidth: 2, priceLineVisible: false, title: 'D' }, pane);
      k.setData(pointsOf(candles, s.k));
      d.setData(pointsOf(candles, s.d));
      k.createPriceLine({ price: p.stochMax, color: COLORS.gold, lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: 'buy' });
      k.createPriceLine({ price: 0.8, color: COLORS.level, lineWidth: 1, lineStyle: 2, axisLabelVisible: false });
      return [k, d];
    },
  },
  macd: {
    title: 'MACD (12,26,9)',
    build(chart, pane, candles, s) {
      const hist = chart.addSeries(HistogramSeries,
        { priceLineVisible: false, lastValueVisible: false, title: 'Hist' }, pane);
      hist.setData(candles.flatMap((c, i) =>
        Number.isNaN(s.hist[i]) ? [] : [{
          time: toTime(c.openTime), value: s.hist[i],
          color: s.hist[i] >= 0 ? COLORS.up + 'aa' : COLORS.down + 'aa',
        }]));
      const macd = chart.addSeries(LineSeries,
        { color: COLORS.line1, lineWidth: 2, priceLineVisible: false, title: 'MACD' }, pane);
      const sig = chart.addSeries(LineSeries,
        { color: COLORS.line2, lineWidth: 2, priceLineVisible: false, title: 'Signal' }, pane);
      macd.setData(pointsOf(candles, s.macd));
      sig.setData(pointsOf(candles, s.signal));
      return [hist, macd, sig];
    },
  },
};

/**
 * Candlestick chart with optional indicator panes underneath.
 * `buy[i]` true → candle i is painted gold.
 */
export default function Chart({ candles, series, buy, enabled, sim, ranged = false, params = DEFAULT_PARAMS, ownTrades = [], interval = '1d' }) {
  // Only the trading window is displayed when a range is set. `first` is the offset
  // between displayed (logical) indices and the full-array indices the sim uses.
  const first = ranged ? Math.min(Math.max(0, sim.firstIndex), candles.length) : 0;
  const last = ranged ? Math.min(candles.length - 1, sim.lastIndex) : candles.length - 1;
  const view = useMemo(() => candles.slice(first, last + 1), [candles, first, last]);
  const viewSeries = useMemo(
    () => Object.fromEntries(Object.entries(series).map(([k, v]) => [k, v.slice(first, last + 1)])),
    [series, first, last]
  );
  // Pending bars after the newest stored candle (only when the view reaches it) for trades newer than the store.
  const tail = useMemo(
    () => (last === candles.length - 1 ? pendingBars(view, ownTrades, interval) : []),
    [view, last, candles.length, ownTrades, interval]
  );

  const containerRef = useRef(null);
  const chartRef = useRef(null);
  const candleSeriesRef = useRef(null);
  const paneSeriesRef = useRef([]);
  const markersRef = useRef(null);
  const priceLinesRef = useRef([]);

  // Create the chart once.
  useEffect(() => {
    const chart = createChart(containerRef.current, {
      autoSize: true,
      layout: {
        background: { type: ColorType.Solid, color: COLORS.bg },
        textColor: COLORS.text,
        panes: { separatorColor: COLORS.grid, separatorHoverColor: '#2c3a4d', enableResize: true },
      },
      grid: { vertLines: { color: COLORS.grid }, horzLines: { color: COLORS.grid } },
      crosshair: { mode: CrosshairMode.Normal },
      rightPriceScale: { borderColor: COLORS.grid },
      timeScale: { borderColor: COLORS.grid, timeVisible: true, secondsVisible: false },
    });
    const candleSeries = chart.addSeries(CandlestickSeries, {
      upColor: COLORS.up, downColor: COLORS.down,
      borderVisible: true, borderUpColor: COLORS.up, borderDownColor: COLORS.down,
      wickUpColor: COLORS.up, wickDownColor: COLORS.down,
    });
    chartRef.current = chart;
    if (import.meta.env.DEV) window.__chart = chart; // debugging aid
    candleSeriesRef.current = candleSeries;
    markersRef.current = createSeriesMarkers(candleSeries, []);
    if (import.meta.env.DEV) { window.__markers = markersRef.current; window.__cs = candleSeries; }
    // Entry/stop/target lines follow the crosshair: the trades active on the hovered candle.
    chart.subscribeCrosshairMove((param) => {
      const { view, first } = stateRef.current;
      const i = param.logical;
      hoverRef.current = Number.isInteger(i) && i >= 0 && i < view.length ? first + i : null;
      drawPriceLines();
    });
    return () => { chart.remove(); chartRef.current = null; };
  }, []);

  // Latest sim/candles for the crosshair handler (which is subscribed once).
  const stateRef = useRef({ view, first, sim });
  stateRef.current = { view, first, sim };
  const hoverRef = useRef(null);      // hovered candle index, null when the pointer is off the candles
  const linesKeyRef = useRef('');     // which trades the lines currently show, to skip redundant redraws

  /** Entry (+ stop/target when it's a single trade) lines for the trades active on the hovered candle, else the open trades. */
  const drawPriceLines = (force = false) => {
    const cs = candleSeriesRef.current;
    if (!cs) return;
    const { sim } = stateRef.current;
    const i = hoverRef.current;
    const active = i === null
      ? sim.openTrades
      : [...sim.trades, ...sim.openTrades].filter((t) => t.entryIndex <= i && (t.exitIndex === undefined || i <= t.exitIndex));
    const key = active.map((t) => t.entryIndex).join(',');
    if (!force && key === linesKeyRef.current) return;
    linesKeyRef.current = key;

    for (const l of priceLinesRef.current) cs.removePriceLine(l);
    priceLinesRef.current = [];
    const mk = (price, color, title) => cs.createPriceLine({ price, color, lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title });
    // One trade: entry + stop + target. Several: entry lines only, to keep the price scale readable.
    for (const t of active) {
      priceLinesRef.current.push(mk(t.entry, COLORS.gold, 'entry'));
      if (active.length === 1) {
        priceLinesRef.current.push(mk(t.stopPrice, COLORS.down, 'stop'), mk(t.targetPrice, COLORS.up, 'target'));
      }
    }
  };

  // Candle data: executed buys are gold, signals skipped (trade already open) get a gold outline.
  useEffect(() => {
    const cs = candleSeriesRef.current;
    if (!cs) return;
    const bought = new Set([...sim.trades, ...sim.openTrades].map((t) => t.entryIndex));
    const skipped = new Set(sim.skipped);
    // Clear first: lightweight-charts (5.x) leaves a stale time-scale point list when
    // setData() replaces same-time data on the only series in the chart, and the next
    // setData() with more series present then drops every point (blank chart).
    cs.setData([]);
    cs.setData([
      ...view.map((c, i) => {
        const bar = { time: toTime(c.openTime), open: c.open, high: c.high, low: c.low, close: c.close };
        if (bought.has(first + i)) {
          bar.color = COLORS.gold;
          bar.wickColor = COLORS.gold;
          bar.borderColor = COLORS.goldBorder;
        } else if (skipped.has(first + i)) {
          bar.borderColor = COLORS.gold;
        }
        return bar;
      }),
      ...tail.map((b) => (b.whitespace
        ? { time: toTime(b.openTime) }
        : { time: toTime(b.openTime), open: b.open, high: b.high, low: b.low, close: b.close, color: COLORS.pending, wickColor: COLORS.pending, borderColor: COLORS.pending })),
    ]);
  }, [view, tail, first, buy, sim]);

  // Trade markers (simulated + the user's real trades); price lines are redrawn for the new sim too.
  useEffect(() => {
    const cs = candleSeriesRef.current;
    if (!cs || !markersRef.current) return;
    const markers = [];
    const pct = (p) => `${p > 0 ? '+' : ''}${(p * 100).toFixed(1)}%`;
    for (const t of sim.trades) {
      markers.push({ time: toTime(candles[t.entryIndex].openTime), position: 'belowBar', shape: 'arrowUp', color: COLORS.gold, text: 'buy' });
      markers.push({
        time: toTime(candles[t.exitIndex].openTime), position: 'aboveBar', shape: 'arrowDown',
        color: t.reason === 'target' ? COLORS.up : COLORS.down, text: `${t.eur > 0 ? "+" : t.eur < 0 ? "−" : ""}€${Math.abs(t.eur).toFixed(0)}`,
      });
    }
    for (const t of sim.openTrades) {
      markers.push({ time: toTime(candles[t.entryIndex].openTime), position: 'belowBar', shape: 'arrowUp', color: COLORS.gold, text: 'buy (open)' });
    }
    if (view.length) markers.push(...ownTradeMarkers([...view, ...tail], ownTrades));   // only displayed candles (+ pending bars) carry data
    markers.sort((a, b) => a.time - b.time);
    markersRef.current.setMarkers(markers);
    drawPriceLines(true);
  }, [candles, view, tail, sim, ownTrades]);

  // Reset the view when the coin/timeframe/trade range changes: the whole trading
  // window when a range is set (only those candles are displayed), else the last 150 candles.
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart || view.length === 0) return;
    const n = view.length + tail.length;
    chart.timeScale().setVisibleLogicalRange({ from: ranged ? 0 : Math.max(0, n - 150), to: n + 3 });
  }, [view, tail, ranged]);

  // Indicator panes follow the toggles.
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart || view.length === 0) return;
    let pane = 1;
    for (const id of ['rsi', 'srsi', 'macd']) {
      if (!enabled.includes(id)) continue;
      paneSeriesRef.current.push(...PANES[id].build(chart, pane, view, viewSeries, params));
      pane++;
    }
    chart.panes().forEach((p, i) => p.setStretchFactor(i === 0 ? 3 : 1));
    return () => {
      if (!chartRef.current) return;
      for (const s of paneSeriesRef.current) chart.removeSeries(s);
      paneSeriesRef.current = [];
    };
  }, [enabled, view, viewSeries, params]);

  return <div className="chart" ref={containerRef} />;
}
