import { useEffect, useRef, useState } from 'react';

const MIN_YEAR = 2010;
const pad = (n, w) => String(n).padStart(w, '0');

/**
 * yyyy / mm / dd date field. Each segment can be typed into; clicking a segment
 * opens a list of just that segment's values (years from 2010 to now, months
 * 1–12, days 1–28…31 for the chosen month). Emits 'YYYY-MM-DD' once all three
 * parts are valid, '' when cleared.
 *
 * `edge` says which end of a range this is: picking a year fills mm/dd with the
 * start ('start': 01-01) or end ('end': 12-31) of that year, and picking a month
 * fills an empty dd with the first or last day of that month.
 */
export default function DateField({ value, onChange, edge = 'start', min = '', max = '' }) {
  const [parts, setParts] = useState(() => split(value));
  const [open, setOpen] = useState(null);   // 'y' | 'm' | 'd' | null
  const rootRef = useRef(null);

  // Follow the value from outside (URL, presets), unless we're mid-edit of a partial date.
  useEffect(() => { setParts(split(value)); }, [value]);

  // Close the list on outside click / Escape.
  useEffect(() => {
    if (!open) return;
    const onDown = (e) => !rootRef.current?.contains(e.target) && setOpen(null);
    const onKey = (e) => e.key === 'Escape' && setOpen(null);
    document.addEventListener('mousedown', onDown);
    document.addEventListener('keydown', onKey);
    return () => { document.removeEventListener('mousedown', onDown); document.removeEventListener('keydown', onKey); };
  }, [open]);

  const commit = (next) => {
    // Picking a shorter month (or Feb in a non-leap year) clamps the day instead of leaving an invalid date.
    const maxDay = daysIn(Number(next.y), Number(next.m));
    if (Number(next.d) > maxDay) next = { ...next, d: pad(maxDay, 2) };
    setParts(next);
    const iso = join(next);
    if (iso !== null && iso !== value) onChange(iso);   // null = incomplete, keep editing
  };
  const set = (key, raw) => {
    const digits = raw.replace(/\D/g, '').slice(0, key === 'y' ? 4 : 2);
    commit({ ...parts, [key]: digits });
  };
  const pick = (key, v) => {
    const next = { ...parts, [key]: v };
    if (key === 'y') {
      next.m = edge === 'end' ? '12' : '01';
      next.d = edge === 'end' ? '31' : '01';
    } else if (key === 'm' && !next.d) {
      next.d = edge === 'end' ? pad(daysIn(Number(next.y), Number(v)), 2) : '01';
    }
    commit(next);
    setOpen(null);
  };
  // Leaving the field with a half-typed date drops the partial input and shows the real value again.
  const onBlur = (e) => {
    if (rootRef.current?.contains(e.relatedTarget)) return;
    if (join(parts) === null) setParts(split(value));
  };
  const clear = () => { setParts({ y: '', m: '', d: '' }); setOpen(null); if (value) onChange(''); };

  const y = Number(parts.y), m = Number(parts.m);
  const thisYear = new Date().getUTCFullYear();
  const options = {
    y: range(MIN_YEAR, thisYear).reverse().map((v) => [String(v), String(v)]),
    m: range(1, 12).map((v) => [pad(v, 2), pad(v, 2)]),
    d: range(1, daysIn(y, m)).map((v) => [pad(v, 2), pad(v, 2)]),
  };
  const outOfBounds = (min && value && value < min) || (max && value && value > max);

  return (
    <span className={`datefield ${outOfBounds ? 'invalid' : ''}`} ref={rootRef} onBlur={onBlur} title="click a part to pick it from a list, or type it">
      <Seg k="y" width={4} value={parts.y} placeholder="yyyy" onType={set} onPick={setOpen} />
      <span className="sep">-</span>
      <Seg k="m" width={2} value={parts.m} placeholder="mm" onType={set} onPick={setOpen} />
      <span className="sep">-</span>
      <Seg k="d" width={2} value={parts.d} placeholder="dd" onType={set} onPick={setOpen} />
      {(parts.y || parts.m || parts.d) && <button className="clear" onClick={clear} title="clear" tabIndex={-1}>×</button>}
      {open && (
        <List
          items={options[open]}
          selected={open === 'y' ? parts.y : pad(Number(parts[open]) || 0, 2)}
          onPick={(v) => pick(open, v)}
        />
      )}
    </span>
  );
}

function Seg({ k, width, value, placeholder, onType, onPick }) {
  return (
    <input
      className={`seg seg-${k}`}
      inputMode="numeric"
      size={width}
      maxLength={width}
      value={value}
      placeholder={placeholder}
      onChange={(e) => onType(k, e.target.value)}
      onClick={() => onPick(k)}
      onFocus={(e) => { e.target.select(); onPick(k); }}
    />
  );
}

function List({ items, selected, onPick }) {
  const ref = useRef(null);
  useEffect(() => { ref.current?.querySelector('.selected')?.scrollIntoView({ block: 'center' }); }, []);
  return (
    <ul className="datelist" ref={ref}>
      {items.map(([v, label]) => (
        <li key={v} className={v === selected ? 'selected' : ''} onMouseDown={(e) => { e.preventDefault(); onPick(v); }}>
          {label}
        </li>
      ))}
    </ul>
  );
}

const range = (a, b) => Array.from({ length: Math.max(0, b - a + 1) }, (_, i) => a + i);
const daysIn = (y, m) => (m >= 1 && m <= 12 ? new Date(Date.UTC(y || 2000, m, 0)).getUTCDate() : 31);
const split = (iso) => (iso ? { y: iso.slice(0, 4), m: iso.slice(5, 7), d: iso.slice(8, 10) } : { y: '', m: '', d: '' });
/** 'YYYY-MM-DD' when all parts form a real date, '' when all empty, null when incomplete/invalid. */
function join({ y, m, d }) {
  if (!y && !m && !d) return '';
  if (y.length !== 4 || !m || !d) return null;
  const Y = Number(y), M = Number(m), D = Number(d);
  if (Y < MIN_YEAR || M < 1 || M > 12 || D < 1 || D > daysIn(Y, M)) return null;
  return `${Y}-${pad(M, 2)}-${pad(D, 2)}`;
}
