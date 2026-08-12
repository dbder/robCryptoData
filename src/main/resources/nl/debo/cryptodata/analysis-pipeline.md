# CryptoAnalysisBitvavo — the analysis pipeline

`CryptoAnalysisBitvavo` is a thin entry point: it wires the exchange-independent
`CryptoAnalysis` pipeline to the **local candle store** (`output/klines-bitvavo/`)
through a self-updating `LocalKlineSource`, and runs it for the intervals
`1d`, `1W` and `1M`.

The interesting work happens in three layers:

| Layer | Class | Job |
|---|---|---|
| Orchestration | `CryptoAnalysis` | fan out symbol × interval, gather rows, write CSV/XLSX |
| Extraction | `IndicatorAnalyzer` | compute all indicator series, pick the latest complete row |
| Math | `Indicators`, `SignalNormalizer` impls | the actual algorithms (see the other docs) |

## Data flow

```mermaid
flowchart TD
    SYM[symbols-bitvavo\none market per line] --> CA[CryptoAnalysis.run]
    CA -->|"symbol × interval (1d, 1W, 1M)"| LKS[LocalKlineSource]
    LKS -->|update on demand| IMP[KlineHistoryImporter]
    IMP -->|missing closed candles| API[(Bitvavo REST API)]
    IMP --> CSVSTORE[(output/klines-bitvavo/*.csv)]
    CSVSTORE --> LKS
    LKS -->|"newest 199 closed candles"| IA[IndicatorAnalyzer.latestRow]
    IA --> ROW[ResultRow: RSI, StochRSI, K, D,\nMACD, Signal, Histogram, MADR, MACDstat]
    NEWS[NewsClient\nheadlines < 48h per base coin] --> ROW
    ROW --> OUT1[(data-bitvavo-local&lt;date&gt;.csv)]
    ROW --> OUT2[(data-bitvavo-local&lt;date&gt;.xlsx)]
```

## Uncompleted candles are never used

The invariant is enforced in one place: `IndicatorAnalyzer.latestRow` keeps
only candles whose **close time has passed** (`closeTime ≤ now`) before any
indicator is computed. An uncompleted candle can therefore never leak into the
analysis, regardless of what the source returns:

```text
API run (open candle present):   [c c c c c ... c c O]   O = open candle,
                                                    └── closeTime > now ⇒ filtered
API run (no trades yet, open
candle omitted by Bitvavo):      [c c c c c ... c c c]   nothing filtered,
                                                          no closed candle lost
local run:                       [c c c c c ... c c c]   store holds closed
                                                          candles only
```

Because the filter checks time instead of position, the local store needs no
trickery: `LocalKlineSource` simply returns the newest `limit − 1` closed
candles — the same closed-candle window an API request for `limit` candles
yields once its open candle is filtered away.

The store itself upholds the same invariant on write: `KlineHistoryImporter`
only appends candles with `closeTime ≤ now`, so the still-open candle is never
written. It pages history **backwards** from now (Bitvavo's `limit` keeps the
newest candles of a range), deduplicates by open time in a `TreeMap`, and
skips the network entirely when the candle after the last saved one cannot
have closed yet.

## Concurrency

Every symbol × interval combination is an independent task on a **virtual
thread** (`Executors.newVirtualThreadPerTaskExecutor`). Results are collected
in a synchronized list; ordering in the report is completion order, not input
order. News is fetched **once per base coin** (e.g. `SOL-EUR → SOL`) before the
fan-out and shared by all interval rows of that coin.

## Picking "the latest row"

`IndicatorAnalyzer.latestRow` computes all series over the closed candles and
walks **backwards from the newest candle** to the first index where every core
indicator (RSI, StochRSI, K, D, MACD, signal) is non-NaN:

```mermaid
flowchart LR
    A["filter: keep candles\nwith closeTime ≤ now"] --> B[compute all series\nNaN-padded warmups]
    B --> C{scan i = last .. 0\nall core values non-NaN?}
    C -- no --> C
    C -- yes --> D[build ResultRow at i]
    D --> E{MADR / MACDstat NaN?}
    E -- "yes (long warmup)" --> F[fall back to neutral 0.5]
    E -- no --> G[use value\nMACDstat is flipped: 1 − v]
```

The two normalized statistics (MADR, MACDstat) need indicator warmup **plus** a
50-candle normalizer window, so on sparse series (e.g. monthly candles of a
young coin) they can still be NaN where everything else is complete. Rather
than dropping the row, they fall back to the neutral `0.5`.

## Parameters (constants in `CryptoAnalysis`)

| Constant | Value | Used by |
|---|---|---|
| `RSI_PERIOD` | 14 | [RSI](rsi.md) |
| `STOCH_RSI_PERIOD` | 14 | [Stochastic RSI](stochastic-rsi.md) |
| `K_PERIOD` / `D_PERIOD` | 3 / 3 | K and D smoothing |
| `MACD_FAST/SLOW/SIGNAL` | 12 / 26 / 9 | [MACD](macd.md) |
| `MADR_SMA_PERIOD` | 50 | [MADR](signal-normalization.md) |
| `NORMALIZER_WINDOW` | 50 | [Z-score normalizer](signal-normalization.md) |
| `KLINE_LIMIT` | 200 | candles fetched per series |
| `NEWS_MAX_AGE` | 48 h | headline cutoff |

200 candles is deliberate headroom: the slowest chain (MADR = SMA 50 warmup +
z-score window 50 ≈ 99 candles; MACD stat similar) still leaves a comfortable
margin on a full series.

## Further reading

- [rsi.md](rsi.md) — Relative Strength Index with Wilder smoothing
- [stochastic-rsi.md](stochastic-rsi.md) — Stochastic RSI and the K/D lines
- [macd.md](macd.md) — EMA, MACD line, signal line, histogram
- [signal-normalization.md](signal-normalization.md) — MADR, scaled MACD
  histogram, and the three interchangeable normalizers
