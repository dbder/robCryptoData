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
import { RSI_OVERSOLD, STOCH_OVERSOLD } from './signals.js';

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
};

const toTime = (ms) => Math.floor(ms / 1000);

function pointsOf(candles, values) {
  const out = [];
  for (let i = 0; i < candles.length; i++) {
    if (!Number.isNaN(values[i])) out.push({ time: toTime(candles[i].openTime), value: values[i] });
  }
  return out;
}

const PANES = {
  rsi: {
    title: 'RSI (14)',
    build(chart, pane, candles, s) {
      const line = chart.addSeries(LineSeries,
        { color: COLORS.line1, lineWidth: 2, priceLineVisible: false, lastValueVisible: true, title: 'RSI' }, pane);
      line.setData(pointsOf(candles, s.rsi));
      line.createPriceLine({ price: RSI_OVERSOLD, color: COLORS.gold, lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: 'buy' });
      line.createPriceLine({ price: 70, color: COLORS.level, lineWidth: 1, lineStyle: 2, axisLabelVisible: false });
      return [line];
    },
  },
  srsi: {
    title: 'Stoch RSI (14,3,3)',
    build(chart, pane, candles, s) {
      const k = chart.addSeries(LineSeries,
        { color: COLORS.line1, lineWidth: 2, priceLineVisible: false, title: 'K' }, pane);
      const d = chart.addSeries(LineSeries,
        { color: COLORS.line2, lineWidth: 2, priceLineVisible: false, title: 'D' }, pane);
      k.setData(pointsOf(candles, s.k));
      d.setData(pointsOf(candles, s.d));
      k.createPriceLine({ price: STOCH_OVERSOLD, color: COLORS.gold, lineWidth: 1, lineStyle: 2, axisLabelVisible: true, title: 'buy' });
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
export default function Chart({ candles, series, buy, enabled, sim, ranged = false }) {
  // Only the trading window is displayed when a range is set. `first` is the offset
  // between displayed (logical) indices and the full-array indices the sim uses.
  const first = ranged ? Math.min(Math.max(0, sim.firstIndex), candles.length) : 0;
  const last = ranged ? Math.min(candles.length - 1, sim.lastIndex) : candles.length - 1;
  const view = useMemo(() => candles.slice(first, last + 1), [candles, first, last]);
  const viewSeries = useMemo(
    () => Object.fromEntries(Object.entries(series).map(([k, v]) => [k, v.slice(first, last + 1)])),
    [series, first, last]
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
    cs.setData(view.map((c, i) => {
      const bar = { time: toTime(c.openTime), open: c.open, high: c.high, low: c.low, close: c.close };
      if (bought.has(first + i)) {
        bar.color = COLORS.gold;
        bar.wickColor = COLORS.gold;
        bar.borderColor = COLORS.goldBorder;
      } else if (skipped.has(first + i)) {
        bar.borderColor = COLORS.gold;
      }
      return bar;
    }));
  }, [view, first, buy, sim]);

  // Trade markers; price lines are redrawn for the new sim too.
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
    markers.sort((a, b) => a.time - b.time);
    markersRef.current.setMarkers(markers);
    drawPriceLines(true);
  }, [candles, sim]);

  // Reset the view when the coin/timeframe/trade range changes: the whole trading
  // window when a range is set (only those candles are displayed), else the last 150 candles.
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart || view.length === 0) return;
    const n = view.length;
    chart.timeScale().setVisibleLogicalRange({ from: ranged ? 0 : Math.max(0, n - 150), to: n + 3 });
  }, [view, ranged]);

  // Indicator panes follow the toggles.
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart || view.length === 0) return;
    let pane = 1;
    for (const id of ['rsi', 'srsi', 'macd']) {
      if (!enabled.includes(id)) continue;
      paneSeriesRef.current.push(...PANES[id].build(chart, pane, view, viewSeries));
      pane++;
    }
    chart.panes().forEach((p, i) => p.setStretchFactor(i === 0 ? 3 : 1));
    return () => {
      if (!chartRef.current) return;
      for (const s of paneSeriesRef.current) chart.removeSeries(s);
      paneSeriesRef.current = [];
    };
  }, [enabled, view, viewSeries]);

  return <div className="chart" ref={containerRef} />;
}
