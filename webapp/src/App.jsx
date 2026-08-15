import { useEffect, useMemo, useState } from 'react';
import Chart from './Chart.jsx';
import { computeAll } from './indicators.js';
import { INDICATORS, combineBuySignals } from './signals.js';

const EMPTY = { rsi: [], stochRsi: [], k: [], d: [], macd: [], signal: [], hist: [] };
const VALID_IND = INDICATORS.map((i) => i.id);

// Initial state from the URL, so a view can be bookmarked / reloaded.
function fromUrl() {
  const q = new URLSearchParams(window.location.search);
  return {
    market: q.get('market') || 'BTC-EUR',
    interval: q.get('interval') || '1d',
    enabled: (q.get('ind') || '').split(',').filter((x) => VALID_IND.includes(x)),
    mode: q.get('mode') === 'any' ? 'any' : 'all',
  };
}
const initial = fromUrl();

export default function App() {
  const [markets, setMarkets] = useState([]);
  const [filter, setFilter] = useState('');
  const [market, setMarket] = useState(initial.market);
  const [interval, setTimeframe] = useState(initial.interval);
  const [candles, setCandles] = useState([]);
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);
  const [enabled, setEnabled] = useState(initial.enabled);
  const [mode, setMode] = useState(initial.mode);

  // Keep the URL in sync with the selection.
  useEffect(() => {
    const q = new URLSearchParams({ market, interval, ind: enabled.join(','), mode });
    window.history.replaceState(null, '', `?${q}`);
  }, [market, interval, enabled, mode]);

  // Market list from the local candle store.
  useEffect(() => {
    fetch('/api/markets')
      .then((r) => r.json())
      .then((list) => {
        if (list.error) throw new Error(list.error);
        setMarkets(list);
        if (!list.some((m) => m.market === market) && list.length) setMarket(list[0].market);
      })
      .catch((e) => setError(friendly(e)));
  }, []);

  // Candles for the selection.
  useEffect(() => {
    if (!market || !interval) return;
    let cancelled = false;
    setLoading(true);
    fetch(`/api/klines?market=${market}&interval=${interval}`)
      .then((r) => r.json())
      .then((data) => {
        if (cancelled) return;
        if (data.error) throw new Error(data.error);
        setCandles(data);
        setError(null);
      })
      .catch((e) => !cancelled && setError(friendly(e)))
      .finally(() => !cancelled && setLoading(false));
    return () => { cancelled = true; };
  }, [market, interval]);

  const series = useMemo(() => (candles.length ? computeAll(candles) : EMPTY), [candles]);
  const buy = useMemo(() => combineBuySignals(series, enabled, mode), [series, enabled, mode]);
  const buyCount = useMemo(() => buy.filter(Boolean).length, [buy]);

  const intervals = markets.find((m) => m.market === market)?.intervals ?? ['1d'];
  const visibleMarkets = markets.filter((m) =>
    m.market.toLowerCase().includes(filter.trim().toLowerCase()));

  const toggle = (id) =>
    setEnabled((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));

  const last = candles.at(-1);

  return (
    <div className="app">
      <header className="toolbar">
        <div className="group">
          <label>Coin</label>
          <input
            className="filter"
            placeholder="filter…"
            value={filter}
            onChange={(e) => setFilter(e.target.value)}
          />
          <select value={market} onChange={(e) => setMarket(e.target.value)}>
            {visibleMarkets.map((m) => (
              <option key={m.market} value={m.market}>{m.market}</option>
            ))}
          </select>
        </div>
        <div className="group">
          <label>Timeframe</label>
          <div className="segmented">
            {intervals.map((iv) => (
              <button
                key={iv}
                className={iv === interval ? 'active' : ''}
                onClick={() => setTimeframe(iv)}
              >
                {iv}
              </button>
            ))}
          </div>
        </div>
        <div className="status">
          {loading && <span>loading…</span>}
          {!loading && last && (
            <span>
              {candles.length} candles · last close{' '}
              <strong>{fmt(last.close)}</strong> · {new Date(last.closeTime).toISOString().slice(0, 16).replace('T', ' ')} UTC
            </span>
          )}
          {error && <span className="error">{error}</span>}
        </div>
      </header>

      <main className="chart-wrap">
        <Chart candles={candles} series={series} buy={buy} enabled={enabled} />
      </main>

      <footer className="toolbar">
        <div className="group">
          <label>Buy signals</label>
          {INDICATORS.map((ind) => (
            <button
              key={ind.id}
              className={`indicator ${enabled.includes(ind.id) ? 'on' : ''}`}
              title={ind.hint}
              onClick={() => toggle(ind.id)}
            >
              {ind.label}
            </button>
          ))}
        </div>
        <div className="group">
          <label>Golden when</label>
          <div className="segmented">
            <button className={mode === 'all' ? 'active' : ''} onClick={() => setMode('all')} title="every enabled indicator fires on the same candle">all fire</button>
            <button className={mode === 'any' ? 'active' : ''} onClick={() => setMode('any')} title="at least one enabled indicator fires">any fires</button>
          </div>
        </div>
        <div className="status">
          {enabled.length === 0
            ? <span>turn on an indicator to highlight buy candles</span>
            : <span><span className="swatch" /> {buyCount} golden candle{buyCount === 1 ? '' : 's'}</span>}
        </div>
      </footer>
    </div>
  );
}

/** fetch() failing outright means the Vite server (which serves /api) is not reachable. */
function friendly(e) {
  if (e instanceof TypeError || /NetworkError|Failed to fetch/i.test(e.message)) {
    return 'Cannot reach the app server. Start it with "npm run dev" in webapp/ and open the URL it prints (usually http://localhost:5173) — the page must be served by Vite, not opened as a file.';
  }
  return e.message;
}

function fmt(v) {
  if (v >= 1000) return v.toLocaleString('en-US', { maximumFractionDigits: 0 });
  if (v >= 1) return v.toFixed(2);
  return v.toPrecision(4);
}
