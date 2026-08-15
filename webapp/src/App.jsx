import { useEffect, useMemo, useState } from 'react';
import Chart from './Chart.jsx';
import AllCoinsModal from './AllCoins.jsx';
import { computeAll } from './indicators.js';
import { INDICATORS, combineBuySignals } from './signals.js';
import { simulateTrades, DEFAULT_STOP_LOSS, DEFAULT_TAKE_PROFIT, DEFAULT_STAKE_EUR, DEFAULT_FEE } from './trades.js';

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
    stopPct: numOr(q.get('stop'), DEFAULT_STOP_LOSS * 100),
    targetPct: numOr(q.get('target'), DEFAULT_TAKE_PROFIT * 100),
    stake: numOr(q.get('stake'), DEFAULT_STAKE_EUR),
    feePct: numOr(q.get('fee'), DEFAULT_FEE * 100),
    skipWhileOpen: q.get('skip') !== '0',
  };
}
function numOrZero(v, fallback) {
  const n = Number(v);
  return v !== '' && Number.isFinite(n) && n >= 0 ? n : fallback;
}
function numOr(v, fallback) {
  const n = Number(v);
  return v !== null && Number.isFinite(n) && n > 0 ? n : fallback;
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
  const [stopPct, setStopPct] = useState(initial.stopPct);
  const [targetPct, setTargetPct] = useState(initial.targetPct);
  const [stake, setStake] = useState(initial.stake);
  const [feePct, setFeePct] = useState(initial.feePct);
  const [skipWhileOpen, setSkipWhileOpen] = useState(initial.skipWhileOpen);
  const [showTrades, setShowTrades] = useState(false);
  const [showAllCoins, setShowAllCoins] = useState(false);

  // Keep the URL in sync with the selection.
  useEffect(() => {
    const q = new URLSearchParams({ market, interval, ind: enabled.join(','), mode, stop: stopPct, target: targetPct, stake, fee: feePct, skip: skipWhileOpen ? '1' : '0' });
    window.history.replaceState(null, '', `?${q}`);
  }, [market, interval, enabled, mode, stopPct, targetPct, stake, feePct, skipWhileOpen]);

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
  const sim = useMemo(
    () => simulateTrades(candles, buy, { stopLoss: stopPct / 100, takeProfit: targetPct / 100, stake, fee: feePct / 100, skipWhileOpen }),
    [candles, buy, stopPct, targetPct, stake, feePct, skipWhileOpen]
  );

  const intervals = markets.find((m) => m.market === market)?.intervals ?? ['1d'];
  const visibleMarkets = markets.filter((m) =>
    m.market.toLowerCase().includes(filter.trim().toLowerCase()));

  const toggle = (id) =>
    setEnabled((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));

  const last = candles.at(-1);
  // Everything the simulation depends on besides the candles — handed to the all-coins modal.
  const config = useMemo(
    () => ({ enabled, mode, stopPct, targetPct, stake, feePct, skipWhileOpen }),
    [enabled, mode, stopPct, targetPct, stake, feePct, skipWhileOpen]
  );

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
        <Chart candles={candles} series={series} buy={buy} enabled={enabled} sim={sim} />
      </main>

      {showTrades && <TradeLog candles={candles} sim={sim} />}

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
        <label className="check" title="when off, every signal opens its own trade">
          <input type="checkbox" checked={skipWhileOpen} onChange={(e) => setSkipWhileOpen(e.target.checked)} />
          skip signal if trade is active
        </label>
        <div className="group">
          <label>Sell when close</label>
          <span className="pct-input">
            <input type="number" min="0.5" step="0.5" value={stopPct}
              onChange={(e) => setStopPct(numOr(e.target.value, stopPct))} /> % down
          </span>
          <span className="pct-input">
            <input type="number" min="0.5" step="0.5" value={targetPct}
              onChange={(e) => setTargetPct(numOr(e.target.value, targetPct))} /> % up
          </span>
        </div>
        <div className="group">
          <label>Per trade</label>
          <span className="pct-input">
            € <input type="number" min="1" step="100" value={stake}
              onChange={(e) => setStake(numOr(e.target.value, stake))} />
          </span>
          <span className="pct-input" title="charged on the buy and again on the sell">
            fee <input type="number" min="0" step="0.05" value={feePct}
              onChange={(e) => setFeePct(numOrZero(e.target.value, feePct))} /> %
          </span>
        </div>
        <div className="footer-right">
          <button className="all-coins" title="P/L of the current configuration for every coin" onClick={() => setShowAllCoins(true)}>
            Σ all coins
          </button>
          <div className="status">
            {enabled.length === 0
              ? <span>turn on an indicator to simulate buys</span>
              : <TradeSummary sim={sim} skipWhileOpen={skipWhileOpen} onToggleLog={() => setShowTrades((v) => !v)} showTrades={showTrades} />}
          </div>
        </div>
      </footer>

      {showAllCoins && (
        <AllCoinsModal markets={markets} interval={interval} config={config} onClose={() => setShowAllCoins(false)} />
      )}
    </div>
  );
}

const pct = (p) => `${p > 0 ? '+' : ''}${(p * 100).toFixed(1)}%`;
const eur = (v) => `${v > 0 ? '+' : v < 0 ? '−' : ''}€${Math.abs(v).toLocaleString('en-US', { maximumFractionDigits: 0 })}`;
const dateOf = (c) => new Date(c.openTime).toISOString().slice(0, 16).replace('T', ' ');

function TradeSummary({ sim, skipWhileOpen, onToggleLog, showTrades }) {
  const n = sim.trades.length;
  return (
    <span className="summary">
      <span className="swatch" /> {n} trade{n === 1 ? '' : 's'}
      {n > 0 && <> · <span className="win">{sim.wins} target</span> / <span className="loss">{sim.losses} stop</span> · P/L <strong className={sim.eur >= 0 ? 'win' : 'loss'}>{eur(sim.eur)}</strong> {skipWhileOpen && <span title="same trades with the proceeds reinvested">({pct(sim.compounded)} compounded)</span>}</>}
      {sim.openTrades.length === 1 && <> · <strong>open</strong> {eur(sim.open.eur)} ({pct(sim.open.move)} price)</>}
      {sim.openTrades.length > 1 && <> · <strong>{sim.openTrades.length} open</strong> {eur(sim.openEur)}</>}
      {sim.skipped.length > 0 && <> · {sim.skipped.length} signal{sim.skipped.length === 1 ? '' : 's'} skipped</>}
      {' '}<button className="link" onClick={onToggleLog}>{showTrades ? 'hide log' : 'show log'}</button>
    </span>
  );
}

function TradeLog({ candles, sim }) {
  const rows = [...sim.trades].reverse();
  return (
    <section className="trade-log">
      <table>
        <thead>
          <tr><th>#</th><th>Buy</th><th>Entry</th><th>Sell</th><th>Exit</th><th>Price move</th><th>P/L (€{sim.stake.toLocaleString('en-US')}, {(sim.fee * 100).toFixed(2)}% fee ×2)</th><th>Candles held</th></tr>
        </thead>
        <tbody>
          {[...sim.openTrades].reverse().map((t) => (
            <tr className="open" key={`open-${t.entryIndex}`}>
              <td>open</td><td>{dateOf(candles[t.entryIndex])}</td><td>{fmt(t.entry)}</td>
              <td>—</td><td>{fmt(candles.at(-1).close)} (last)</td>
              <td className={t.move >= 0 ? 'win' : 'loss'}>{pct(t.move)}</td>
              <td className={t.eur >= 0 ? 'win' : 'loss'}>{eur(t.eur)} (open)</td>
              <td>{candles.length - 1 - t.entryIndex}</td>
            </tr>
          ))}
          {rows.map((t, i) => (
            <tr key={t.entryIndex}>
              <td>{rows.length - i}</td>
              <td>{dateOf(candles[t.entryIndex])}</td><td>{fmt(t.entry)}</td>
              <td>{dateOf(candles[t.exitIndex])}</td><td>{fmt(t.exit)}</td>
              <td className={t.move >= 0 ? 'win' : 'loss'}>{pct(t.move)} ({t.reason})</td>
              <td className={t.eur >= 0 ? 'win' : 'loss'}>{eur(t.eur)}</td>
              <td>{t.exitIndex - t.entryIndex}</td>
            </tr>
          ))}
          {rows.length === 0 && sim.openTrades.length === 0 && <tr><td colSpan="8">no trades</td></tr>}
        </tbody>
      </table>
    </section>
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
