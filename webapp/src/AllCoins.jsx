import { useEffect, useMemo, useRef, useState } from 'react';
import { computeAll } from './indicators.js';
import { combineBuySignals } from './signals.js';
import { simulateTrades } from './trades.js';
import { CATEGORIES, categoryOf, baseOf, loadOverrides, saveOverrides } from './categories.js';

const CONCURRENCY = 6;

// { candles, series } per "market|interval", shared across modal openings so re-opening is instant.
// The indicator series only depend on the candles, so they are computed once and cached too.
const cache = new Map();

const EXCLUDED_KEY = 'allCoinsExcluded';
const ZERO = { trades: 0, wins: 0, losses: 0, open: 0, openEur: 0, eur: 0, total: 0 };
const sumRows = (rows) => rows.reduce(
  (t, r) => ({
    trades: t.trades + r.trades, wins: t.wins + r.wins, losses: t.losses + r.losses,
    open: t.open + r.open, openEur: t.openEur + r.openEur, eur: t.eur + r.eur, total: t.total + r.total,
  }),
  ZERO
);

/**
 * Modal listing every coin that has candles for the current timeframe, with the
 * P/L of the current configuration (indicators, mode, stop/target, stake, fee) run
 * on each of them. The coins are grouped (stablecoins, alpha, meme, … see categories.js):
 * a table at the top shows the totals per group and lets you include/exclude groups;
 * the coin list below and its totals rows (pinned top and bottom) only cover the
 * included groups. A coin's group can be changed from its row (remembered in localStorage).
 */
export default function AllCoinsModal({ markets, interval, config, onClose }) {
  const [loaded, setLoaded] = useState({});   // market -> { candles, series }
  const [failed, setFailed] = useState({});
  const [sort, setSort] = useState({ key: 'total', dir: 'desc' });
  const [overrides, setOverrides] = useState(loadOverrides);           // base asset -> category id
  const [excluded, setExcluded] = useState(() => new Set(loadExcluded()));   // category ids left out of the totals
  const moveTo = (market, id) => {
    const base = baseOf(market);
    const next = { ...overrides };
    if (categoryOf(market) === id) delete next[base]; else next[base] = id;   // back to the default → no override needed
    setOverrides(next);
    saveOverrides(next);
  };
  const setExcludedAndSave = (next) => { setExcluded(next); localStorage.setItem(EXCLUDED_KEY, JSON.stringify([...next])); };
  const toggleGroup = (id) => {
    const next = new Set(excluded);
    next.has(id) ? next.delete(id) : next.add(id);
    setExcludedAndSave(next);
  };
  const wanted = useMemo(
    () => markets.filter((m) => m.intervals.includes(interval)).map((m) => m.market),
    [markets, interval]
  );

  // Close on Escape.
  useEffect(() => {
    const onKey = (e) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // Fetch candles for every coin, a few at a time.
  const generation = useRef(0);
  useEffect(() => {
    const gen = ++generation.current;
    setLoaded({});
    setFailed({});
    const queue = [...wanted];
    const worker = async () => {
      while (queue.length && generation.current === gen) {
        const market = queue.shift();
        const key = `${market}|${interval}`;
        try {
          if (!cache.has(key)) {
            const r = await fetch(`/api/klines?market=${market}&interval=${interval}`);
            const candles = await r.json();
            if (candles.error) throw new Error(candles.error);
            cache.set(key, { candles, series: computeAll(candles) });
          }
          if (generation.current === gen) setLoaded((cur) => ({ ...cur, [market]: cache.get(key) }));
        } catch (e) {
          if (generation.current === gen) setFailed((cur) => ({ ...cur, [market]: e.message }));
        }
      }
    };
    for (let i = 0; i < CONCURRENCY; i++) worker();
    return () => { generation.current++; };
  }, [wanted, interval]);

  // One simulation per coin with the current configuration.
  const rows = useMemo(() => {
    const { enabled, mode, stopPct, targetPct, stake, feePct, skipWhileOpen, from, to, params } = config;
    return Object.entries(loaded).map(([market, { candles, series }]) => {
      const buy = combineBuySignals(series, enabled, mode, params);
      const sim = simulateTrades(candles, buy, { stopLoss: stopPct / 100, takeProfit: targetPct / 100, stake, fee: feePct / 100, skipWhileOpen, from, to });
      return {
        market, category: categoryOf(market, overrides),
        trades: sim.trades.length, wins: sim.wins, losses: sim.losses,
        open: sim.openTrades.length, openEur: sim.openEur,
        eur: sim.eur, total: sim.eur + sim.openEur,
      };
    });
  }, [loaded, config, overrides]);

  // One line per group that has coins, in the order of CATEGORIES.
  const groups = useMemo(
    () => CATEGORIES
      .map((c) => ({ ...c, rows: rows.filter((r) => r.category === c.id) }))
      .filter((g) => g.rows.length)
      .map((g) => ({ ...g, count: g.rows.length, totals: sumRows(g.rows) })),
    [rows]
  );
  const included = useMemo(() => rows.filter((r) => !excluded.has(r.category)), [rows, excluded]);
  const sorted = useMemo(() => {
    const dir = sort.dir === 'asc' ? 1 : -1;
    const catIndex = (id) => CATEGORIES.findIndex((c) => c.id === id);
    return [...included].sort((a, b) => {
      const av = a[sort.key], bv = b[sort.key];
      const cmp = sort.key === 'category' ? catIndex(av) - catIndex(bv) : typeof av === 'string' ? av.localeCompare(bv) : av - bv;
      return cmp * dir || a.market.localeCompare(b.market);
    });
  }, [included, sort]);
  const totals = sumRows(included);
  const allIncluded = groups.length > 0 && groups.every((g) => !excluded.has(g.id));
  const noneIncluded = groups.every((g) => excluded.has(g.id));
  const overrideCount = Object.keys(overrides).length;

  const done = rows.length + Object.keys(failed).length;
  const loading = done < wanted.length;
  const sortBy = (key) => setSort((s) => ({ key, dir: s.key === key ? (s.dir === 'asc' ? 'desc' : 'asc') : key === 'market' || key === 'category' ? 'asc' : 'desc' }));
  const arrow = (key) => (sort.key === key ? (sort.dir === 'asc' ? ' ▲' : ' ▼') : '');

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h2>All coins · {interval}</h2>
          <span className="muted">
            {config.enabled.length === 0
              ? 'no indicator enabled — turn one on to simulate buys'
              : `${config.enabled.join(' + ')} (${config.mode}) · stop ${config.stopPct}% · target ${config.targetPct}% · €${config.stake} per trade · ${config.feePct}% fee${rangeLabel(config)}`}
          </span>
          <button className="close" onClick={onClose} aria-label="close">×</button>
        </header>
        <div className="modal-progress" style={{ '--p': wanted.length ? done / wanted.length : 1 }}>
          <span>{loading ? `loading ${done} / ${wanted.length}…` : `${rows.length} coins${Object.keys(failed).length ? ` · ${Object.keys(failed).length} failed` : ''}`}</span>
        </div>
        <div className="modal-groups">
          <table>
            <thead>
              <tr>
                <th>
                  <span className="select-all">
                    <input type="checkbox" title={allIncluded ? 'select none' : 'select all'} checked={allIncluded}
                      ref={(el) => { if (el) el.indeterminate = !allIncluded && !noneIncluded; }}
                      onChange={() => setExcludedAndSave(allIncluded ? new Set(groups.map((g) => g.id)) : new Set())} />
                    Group
                    <span className="segmented">
                      <button className={allIncluded ? 'active' : ''} onClick={() => setExcludedAndSave(new Set())}>all</button>
                      <button className={noneIncluded ? 'active' : ''} onClick={() => setExcludedAndSave(new Set(groups.map((g) => g.id)))}>none</button>
                    </span>
                  </span>
                </th>
                <th>Coins</th><th>Trades</th><th>Target</th><th>Stop</th><th>Realized P/L</th><th>Open</th><th>Open P/L</th><th>Total</th>
              </tr>
            </thead>
            <tbody>
              {groups.map((g) => (
                <tr key={g.id} className={excluded.has(g.id) ? 'excluded' : ''} title={g.hint} onClick={() => toggleGroup(g.id)}>
                  <td>
                    <label className="check" onClick={(e) => e.stopPropagation()}>
                      <input type="checkbox" checked={!excluded.has(g.id)} onChange={() => toggleGroup(g.id)} /> {g.label}
                    </label>
                  </td>
                  <td>{g.count}</td>
                  <td>{g.totals.trades}</td>
                  <td className="win">{g.totals.wins || ''}</td>
                  <td className="loss">{g.totals.losses || ''}</td>
                  <td className={sign(g.totals.eur)}>{g.totals.trades ? eur(g.totals.eur) : ''}</td>
                  <td>{g.totals.open || ''}</td>
                  <td className={sign(g.totals.openEur)}>{g.totals.open ? eur(g.totals.openEur) : ''}</td>
                  <td className={sign(g.totals.total)}><strong>{g.totals.trades || g.totals.open ? eur(g.totals.total) : '—'}</strong></td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <TotalsRow label={`Included (${groups.length - [...excluded].filter((id) => groups.some((g) => g.id === id)).length} of ${groups.length} groups)`} second={included.length} totals={totals} />
            </tfoot>
          </table>
          <div className="modal-note">
            click a group to include or exclude it · a coin's group is set in <code>src/categories.js</code>; change it in its row below
            {overrideCount > 0 && <> · {overrideCount} moved by you (<button className="link" onClick={() => { setOverrides({}); saveOverrides({}); }}>reset</button>)</>}
          </div>
        </div>
        <div className="modal-body">
          <table>
            <thead>
              <tr>
                <th onClick={() => sortBy('market')}>Coin{arrow('market')}</th>
                <th onClick={() => sortBy('category')}>Group{arrow('category')}</th>
                <th onClick={() => sortBy('trades')}>Trades{arrow('trades')}</th>
                <th onClick={() => sortBy('wins')}>Target{arrow('wins')}</th>
                <th onClick={() => sortBy('losses')}>Stop{arrow('losses')}</th>
                <th onClick={() => sortBy('eur')}>Realized P/L{arrow('eur')}</th>
                <th onClick={() => sortBy('open')}>Open{arrow('open')}</th>
                <th onClick={() => sortBy('openEur')}>Open P/L{arrow('openEur')}</th>
                <th onClick={() => sortBy('total')}>Total{arrow('total')}</th>
              </tr>
              <TotalsRow label={`Total (${included.length} coins)`} totals={totals} />
            </thead>
            <tbody>
              {sorted.map((r) => (
                <tr key={r.market}>
                  <td>{r.market}</td>
                  <td>
                    <select className="cat" value={r.category} onChange={(e) => moveTo(r.market, e.target.value)} title={`group of ${baseOf(r.market)} (all its markets)`}>
                      {CATEGORIES.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
                    </select>
                  </td>
                  <td>{r.trades}</td>
                  <td className="win">{r.wins || ''}</td>
                  <td className="loss">{r.losses || ''}</td>
                  <td className={sign(r.eur)}>{r.trades ? eur(r.eur) : ''}</td>
                  <td>{r.open || ''}</td>
                  <td className={sign(r.openEur)}>{r.open ? eur(r.openEur) : ''}</td>
                  <td className={sign(r.total)}><strong>{r.trades || r.open ? eur(r.total) : '—'}</strong></td>
                </tr>
              ))}
              {Object.entries(failed).map(([market, msg]) => (
                <tr key={market} className="failed"><td>{market}</td><td colSpan="8">{msg}</td></tr>
              ))}
              {!loading && rows.length === 0 && <tr><td colSpan="9">no coins with {interval} candles</td></tr>}
              {!loading && rows.length > 0 && included.length === 0 && <tr><td colSpan="9">every group is excluded</td></tr>}
            </tbody>
            <tfoot>
              <TotalsRow label={`Total (${included.length} coins)`} totals={totals} />
            </tfoot>
          </table>
        </div>
      </div>
    </div>
  );
}

/** Sum line for both tables: label, an optional second cell, then the seven P/L columns. */
function TotalsRow({ label, second = '', totals }) {
  return (
    <tr className="totals">
      <td>{label}</td>
      <td>{second}</td>
      <td>{totals.trades}</td>
      <td className="win">{totals.wins}</td>
      <td className="loss">{totals.losses}</td>
      <td className={sign(totals.eur)}>{eur(totals.eur)}</td>
      <td>{totals.open}</td>
      <td className={sign(totals.openEur)}>{eur(totals.openEur)}</td>
      <td className={sign(totals.total)}><strong>{eur(totals.total)}</strong></td>
    </tr>
  );
}

function loadExcluded() {
  try {
    const list = JSON.parse(localStorage.getItem(EXCLUDED_KEY) ?? '[]');
    return Array.isArray(list) ? list.filter((id) => CATEGORIES.some((c) => c.id === id)) : [];
  } catch { return []; }
}

const day = (ms) => new Date(ms).toISOString().slice(0, 10);
const rangeLabel = ({ from, to }) =>
  from == null && to == null ? '' : ` · trades ${from == null ? 'until' : `from ${day(from)}`}${to == null ? '' : `${from == null ? '' : ' to'} ${day(to)}`}`;
const sign = (v) => (v > 0 ? 'win' : v < 0 ? 'loss' : '');
const eur = (v) => `${v > 0 ? '+' : v < 0 ? '−' : ''}€${Math.abs(v).toLocaleString('en-US', { maximumFractionDigits: 0 })}`;
