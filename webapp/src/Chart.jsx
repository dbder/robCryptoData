import { useEffect, useRef } from 'react';
import {
  createChart,
  CandlestickSeries,
  LineSeries,
  HistogramSeries,
  ColorType,
  CrosshairMode,
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
export default function Chart({ candles, series, buy, enabled }) {
  const containerRef = useRef(null);
  const chartRef = useRef(null);
  const candleSeriesRef = useRef(null);
  const paneSeriesRef = useRef([]);

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
      upColor: COLORS.up, downColor: COLORS.down, borderVisible: false,
      wickUpColor: COLORS.up, wickDownColor: COLORS.down,
    });
    chartRef.current = chart;
    candleSeriesRef.current = candleSeries;
    return () => { chart.remove(); chartRef.current = null; };
  }, []);

  // Candle data + golden buy candles.
  useEffect(() => {
    const cs = candleSeriesRef.current;
    if (!cs) return;
    cs.setData(candles.map((c, i) => {
      const bar = { time: toTime(c.openTime), open: c.open, high: c.high, low: c.low, close: c.close };
      if (buy[i]) {
        bar.color = COLORS.gold;
        bar.wickColor = COLORS.gold;
        bar.borderColor = COLORS.goldBorder;
      }
      return bar;
    }));
  }, [candles, buy]);

  // Reset the view when the coin/timeframe changes.
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart || candles.length === 0) return;
    const n = candles.length;
    chart.timeScale().setVisibleLogicalRange({ from: Math.max(0, n - 150), to: n + 3 });
  }, [candles]);

  // Indicator panes follow the toggles.
  useEffect(() => {
    const chart = chartRef.current;
    if (!chart || candles.length === 0) return;
    let pane = 1;
    for (const id of ['rsi', 'srsi', 'macd']) {
      if (!enabled.includes(id)) continue;
      paneSeriesRef.current.push(...PANES[id].build(chart, pane, candles, series));
      pane++;
    }
    chart.panes().forEach((p, i) => p.setStretchFactor(i === 0 ? 3 : 1));
    return () => {
      if (!chartRef.current) return;
      for (const s of paneSeriesRef.current) chart.removeSeries(s);
      paneSeriesRef.current = [];
    };
  }, [enabled, candles, series]);

  return <div className="chart" ref={containerRef} />;
}
