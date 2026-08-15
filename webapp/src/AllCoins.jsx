import { useEffect, useMemo, useRef, useState } from 'react';
import { computeAll } from './indicators.js';
import { combineBuySignals } from './signals.js';
import { simulateTrades } from './trades.js';

const CONCURRENCY = 6;

// { candles, series } per "market|interval", shared across modal openings so re-opening is instant.
// The indicator series only depend on the candles, so they are computed once and cached too.
const cache = new Map();

/**
 * Modal listing every coin that has candles for the current timeframe, with the
 * P/L of the current configuration (indicators, mode, stop/target, stake, fee) run
 * on each of them. A totals row is pinned to the top and bottom of the scrollable list.
 */
export default function AllCoinsModal({ markets, interval, config, onClose }) {
  const [loaded, setLoaded] = useState({});   // market -> { candles, series }
  const [failed, setFailed] = useState({});
  const [sort, setSort] = useState({ key: 'total', dir: 'desc' });
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
    const { enabled, mode, stopPct, targetPct, stake, feePct, skipWhileOpen } = config;
    return Object.entries(loaded).map(([market, { candles, series }]) => {
      const buy = combineBuySignals(series, enabled, mode);
      const sim = simulateTrades(candles, buy, { stopLoss: stopPct / 100, takeProfit: targetPct / 100, stake, fee: feePct / 100, skipWhileOpen });
      return {
        market,
        trades: sim.trades.length, wins: sim.wins, losses: sim.losses,
        open: sim.openTrades.length, openEur: sim.openEur,
        eur: sim.eur, total: sim.eur + sim.openEur,
      };
    });
  }, [loaded, config]);

  const sorted = useMemo(() => {
    const dir = sort.dir === 'asc' ? 1 : -1;
    return [...rows].sort((a, b) => {
      const av = a[sort.key], bv = b[sort.key];
      const cmp = typeof av === 'string' ? av.localeCompare(bv) : av - bv;
      return cmp * dir || a.market.localeCompare(b.market);
    });
  }, [rows, sort]);

  const totals = rows.reduce(
    (t, r) => ({
      trades: t.trades + r.trades, wins: t.wins + r.wins, losses: t.losses + r.losses,
      open: t.open + r.open, openEur: t.openEur + r.openEur, eur: t.eur + r.eur, total: t.total + r.total,
    }),
    { trades: 0, wins: 0, losses: 0, open: 0, openEur: 0, eur: 0, total: 0 }
  );

  const done = rows.length + Object.keys(failed).length;
  const loading = done < wanted.length;
  const sortBy = (key) => setSort((s) => ({ key, dir: s.key === key ? (s.dir === 'asc' ? 'desc' : 'asc') : key === 'market' ? 'asc' : 'desc' }));
  const arrow = (key) => (sort.key === key ? (sort.dir === 'asc' ? ' ▲' : ' ▼') : '');

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className="modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
        <header className="modal-head">
          <h2>All coins · {interval}</h2>
          <span className="muted">
            {config.enabled.length === 0
              ? 'no indicator enabled — turn one on to simulate buys'
              : `${config.enabled.join(' + ')} (${config.mode}) · stop ${config.stopPct}% · target ${config.targetPct}% · €${config.stake} per trade · ${config.feePct}% fee`}
          </span>
          <button className="close" onClick={onClose} aria-label="close">×</button>
        </header>
        <div className="modal-progress" style={{ '--p': wanted.length ? done / wanted.length : 1 }}>
          <span>{loading ? `loading ${done} / ${wanted.length}…` : `${rows.length} coins${Object.keys(failed).length ? ` · ${Object.keys(failed).length} failed` : ''}`}</span>
        </div>
        <div className="modal-body">
          <table>
            <thead>
              <tr>
                <th onClick={() => sortBy('market')}>Coin{arrow('market')}</th>
                <th onClick={() => sortBy('trades')}>Trades{arrow('trades')}</th>
                <th onClick={() => sortBy('wins')}>Target{arrow('wins')}</th>
                <th onClick={() => sortBy('losses')}>Stop{arrow('losses')}</th>
                <th onClick={() => sortBy('eur')}>Realized P/L{arrow('eur')}</th>
                <th onClick={() => sortBy('open')}>Open{arrow('open')}</th>
                <th onClick={() => sortBy('openEur')}>Open P/L{arrow('openEur')}</th>
                <th onClick={() => sortBy('total')}>Total{arrow('total')}</th>
              </tr>
              <TotalsRow totals={totals} count={rows.length} />
            </thead>
            <tbody>
              {sorted.map((r) => (
                <tr key={r.market}>
                  <td>{r.market}</td>
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
                <tr key={market} className="failed"><td>{market}</td><td colSpan="7">{msg}</td></tr>
              ))}
              {!loading && rows.length === 0 && <tr><td colSpan="8">no coins with {interval} candles</td></tr>}
            </tbody>
            <tfoot>
              <TotalsRow totals={totals} count={rows.length} />
            </tfoot>
          </table>
        </div>
      </div>
    </div>
  );
}

function TotalsRow({ totals, count }) {
  return (
    <tr className="totals">
      <td>Total ({count} coins)</td>
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

const sign = (v) => (v > 0 ? 'win' : v < 0 ? 'loss' : '');
const eur = (v) => `${v > 0 ? '+' : v < 0 ? '−' : ''}€${Math.abs(v).toLocaleString('en-US', { maximumFractionDigits: 0 })}`;
