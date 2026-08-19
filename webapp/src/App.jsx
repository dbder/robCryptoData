import { useEffect, useMemo, useState } from 'react';
import Chart from './Chart.jsx';
import AllCoinsModal from './AllCoins.jsx';
import DateField from './DateField.jsx';
import { computeAll } from './indicators.js';
import { INDICATORS, DEFAULT_PARAMS, combineBuySignals } from './signals.js';
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
    showMine: q.get('mine') !== '0',
    from: dateOr(q.get('from')),
    to: dateOr(q.get('to')),
    params: {
      rsiMax: numOr(q.get('rsi'), DEFAULT_PARAMS.rsiMax),
      stochMax: numOr(q.get('srsi'), DEFAULT_PARAMS.stochMax),
    },
  };
}
/** 'YYYY-MM-DD' or '' (no bound). */
function dateOr(v) {
  return v && /^\d{4}-\d{2}-\d{2}$/.test(v) && !Number.isNaN(Date.parse(v)) ? v : '';
}
const DAY = 24 * 60 * 60 * 1000;
/** Start of the day (UTC) as ms, or null when unbounded. */
const fromMs = (d) => (d ? Date.parse(d) : null);
/** End of the day (UTC) as ms so the "to" day is included, or null when unbounded. */
const toMs = (d) => (d ? Date.parse(d) + DAY - 1 : null);
const isoDay = (ms) => new Date(ms).toISOString().slice(0, 10);
const RANGE_PRESETS = [
  { label: '1m', months: 1 }, { label: '3m', months: 3 }, { label: '6m', months: 6 },
  { label: '1y', months: 12 }, { label: '2y', months: 24 }, { label: 'all', months: null },
];
function monthsAgo(n) {
  const d = new Date();
  d.setUTCMonth(d.getUTCMonth() - n);
  return isoDay(d.getTime());
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
/** Marks the coins the user actually traded (in the ledger `output/balance-bitvavo/trades.csv`). */
export const OWN_ICON = '★';

/**
 * Coins the user traded first — most recent transaction on top — then the rest alphabetically.
 * `lastTradeAt`: market → ms of its latest own trade.
 */
export function orderMarkets(markets, lastTradeAt) {
  return [...markets].sort((a, b) =>
    (lastTradeAt.get(b.market) ?? -Infinity) - (lastTradeAt.get(a.market) ?? -Infinity) || a.market.localeCompare(b.market));
}

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
  const [ownTrades, setOwnTrades] = useState([]);       // the user's real trades, all markets, oldest first
  const [showMine, setShowMine] = useState(initial.showMine);
  const [from, setFrom] = useState(initial.from);
  const [to, setTo] = useState(initial.to);
  const [params, setParams] = useState(initial.params);   // indicator trigger levels
  const [showTrades, setShowTrades] = useState(false);
  const [showAllCoins, setShowAllCoins] = useState(false);

  // Keep the URL in sync with the selection.
  useEffect(() => {
    const q = new URLSearchParams({ market, interval, ind: enabled.join(','), mode, stop: stopPct, target: targetPct, stake, fee: feePct, skip: skipWhileOpen ? '1' : '0', mine: showMine ? '1' : '0' });
    if (from) q.set('from', from);
    if (to) q.set('to', to);
    q.set('rsi', params.rsiMax);
    q.set('srsi', params.stochMax);
    window.history.replaceState(null, '', `?${q}`);
  }, [market, interval, enabled, mode, stopPct, targetPct, stake, feePct, skipWhileOpen, showMine, from, to, params]);

  // The user's own trades from the ledger; missing ledger → [] (a failure here is not fatal for the charts).
  useEffect(() => {
    fetch('/api/trades')
      .then((r) => r.json())
      .then((list) => Array.isArray(list) && setOwnTrades(list))
      .catch(() => {});
  }, []);

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
  const buy = useMemo(() => combineBuySignals(series, enabled, mode, params), [series, enabled, mode, params]);
  const sim = useMemo(
    () => simulateTrades(candles, buy, { stopLoss: stopPct / 100, takeProfit: targetPct / 100, stake, fee: feePct / 100, skipWhileOpen, from: fromMs(from), to: toMs(to) }),
    [candles, buy, stopPct, targetPct, stake, feePct, skipWhileOpen, from, to]
  );

  const intervals = markets.find((m) => m.market === market)?.intervals ?? ['1d'];
  // Coins the user traded go on top, most recent transaction first.
  const lastTradeAt = useMemo(() => {
    const map = new Map();
    for (const t of ownTrades) map.set(t.market, Math.max(map.get(t.market) ?? 0, t.time));
    return map;
  }, [ownTrades]);
  const orderedMarkets = useMemo(() => orderMarkets(markets, lastTradeAt), [markets, lastTradeAt]);
  const visibleMarkets = orderedMarkets.filter((m) =>
    m.market.toLowerCase().includes(filter.trim().toLowerCase()));
  const marketTrades = useMemo(() => ownTrades.filter((t) => t.market === market), [ownTrades, market]);

  const toggle = (id) =>
    setEnabled((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));

  const last = candles.at(-1);
  // Everything the simulation depends on besides the candles — handed to the all-coins modal.
  const config = useMemo(
    () => ({ enabled, mode, stopPct, targetPct, stake, feePct, skipWhileOpen, from: fromMs(from), to: toMs(to), params }),
    [enabled, mode, stopPct, targetPct, stake, feePct, skipWhileOpen, from, to, params]
  );
  const activePreset = RANGE_PRESETS.find((p) => (p.months === null ? !from && !to : from === monthsAgo(p.months) && !to))?.label;
  const inRange = sim.lastIndex - sim.firstIndex + 1;

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
            onKeyDown={(e) => {
              // Enter picks the coin the dropdown is showing: the current one if it still
              // matches the filter, otherwise the first match.
              if (e.key !== 'Enter') return;
              const shown = visibleMarkets.find((m) => m.market === market) ?? visibleMarkets[0];
              if (shown) setMarket(shown.market);
            }}
          />
          <select value={market} onChange={(e) => setMarket(e.target.value)}>
            {visibleMarkets.map((m) => (
              <option key={m.market} value={m.market}>{lastTradeAt.has(m.market) ? `${OWN_ICON} ${m.market}` : m.market}</option>
            ))}
          </select>
          {marketTrades.length > 0 && (
            <label className="check own" title={`your ${marketTrades.length} real Bitvavo trade${marketTrades.length === 1 ? '' : 's'} in this coin (output/balance-bitvavo/trades.csv), last on ${isoDay(lastTradeAt.get(market))}. Blue arrow = buy, purple = sell; a grey candle is a trade newer than the candle store (built from the trade prices, no closed candle yet).`}>
              <input type="checkbox" checked={showMine} onChange={(e) => setShowMine(e.target.checked)} />
              {OWN_ICON} my {marketTrades.length} trade{marketTrades.length === 1 ? '' : 's'}
            </label>
          )}
        </div>
        <div className="group">
          <label>Timeframe</label>
          <HoverPicker current={interval}>
            {intervals.map((iv) => (
              <button
                key={iv}
                className={iv === interval ? 'active' : ''}
                onClick={() => setTimeframe(iv)}
              >
                {iv}
              </button>
            ))}
          </HoverPicker>
        </div>
        <div className="group" title="buys and sells only happen in this window; earlier candles still warm up the indicators">
          <label>Trade range</label>
          <DateField value={from} max={to} edge="start" onChange={setFrom} />
          <span className="muted">→</span>
          <DateField value={to} min={from} edge="end" onChange={setTo} />
          <HoverPicker current={activePreset ?? 'custom'}>
            {RANGE_PRESETS.map((p) => (
              <button
                key={p.label}
                className={activePreset === p.label ? 'active' : ''}
                onClick={() => { setFrom(p.months === null ? '' : monthsAgo(p.months)); setTo(''); }}
              >
                {p.label}
              </button>
            ))}
          </HoverPicker>
        </div>
        <div className="status">
          {loading && <span>loading…</span>}
          {!loading && last && (
            <span>
              {candles.length} candles{(from || to) && ` (${Math.max(0, inRange)} in range)`} · last close{' '}
              <strong>{fmt(last.close)}</strong> · {new Date(last.closeTime).toISOString().slice(0, 16).replace('T', ' ')} UTC
            </span>
          )}
          {error && <span className="error">{error}</span>}
        </div>
      </header>

      <main className="chart-wrap">
        <Chart candles={candles} series={series} buy={buy} enabled={enabled} sim={sim} ranged={Boolean(from || to)} params={params} ownTrades={showMine ? marketTrades : []} interval={interval} />
      </main>

      {showTrades && <TradeLog candles={candles} sim={sim} />}

      <footer className="toolbar">
        <div className="group">
          <label>Buy signals</label>
          {INDICATORS.map((ind) => (
            <IndicatorButton
              key={ind.id}
              ind={ind}
              on={enabled.includes(ind.id)}
              params={params}
              onToggle={() => toggle(ind.id)}
              onParam={(key, v) => setParams((cur) => ({ ...cur, [key]: v }))}
            />
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
        <div className="group">
          <button className="all-coins" title="P/L of the current configuration for every coin" onClick={() => setShowAllCoins(true)}>
            Σ all coins
          </button>
        </div>
        <div className="status">
          {enabled.length === 0
            ? <span>turn on an indicator to simulate buys</span>
            : <TradeSummary sim={sim} skipWhileOpen={skipWhileOpen} onToggleLog={() => setShowTrades((v) => !v)} showTrades={showTrades} />}
        </div>
      </footer>

      {showAllCoins && (
        <AllCoinsModal markets={markets} interval={interval} config={config} onClose={() => setShowAllCoins(false)} />
      )}
    </div>
  );
}

/** Indicator toggle; for indicators with a trigger level, hovering shows a slider to change it. */
function IndicatorButton({ ind, on, params, onToggle, onParam }) {
  const button = (
    <button className={`indicator ${on ? 'on' : ''}`} title={ind.hint(params)} onClick={onToggle}>
      {ind.label}{ind.param && <span className="level"> {ind.param.format(params[ind.param.key])}</span>}
    </button>
  );
  if (!ind.param) return button;
  const { key, min, max, step, format, label } = ind.param;
  return (
    <span className="hover-picker">
      {button}
      <span className="options">
        <span className="slider">
          <span className="slider-label">{label} <strong>{format(params[key])}</strong></span>
          {/* focus keeps the popover open while dragging even if the pointer strays; blur on release lets it close */}
          <input type="range" min={min} max={max} step={step} value={params[key]}
            onChange={(e) => onParam(key, Number(e.target.value))}
            onPointerUp={(e) => e.target.blur()} />
        </span>
      </span>
    </span>
  );
}

/** Shows only the current value; hovering (or focusing) it reveals the full row of option buttons. */
function HoverPicker({ current, children }) {
  return (
    <span className="hover-picker" tabIndex={0}>
      <span className="current">{current} <span className="caret">▾</span></span>
      <span className="options" onMouseDown={(e) => e.preventDefault()}><span className="segmented">{children}</span></span>
    </span>
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
              <td>—</td><td>{fmt(candles[sim.lastIndex].close)} (last)</td>
              <td className={t.move >= 0 ? 'win' : 'loss'}>{pct(t.move)}</td>
              <td className={t.eur >= 0 ? 'win' : 'loss'}>{eur(t.eur)} (open)</td>
              <td>{sim.lastIndex - t.entryIndex}</td>
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
