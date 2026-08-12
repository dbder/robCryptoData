# RSI — Relative Strength Index (Wilder, period 14)

Implemented in `Indicators.rsi(prices, period)`. Input is the list of **close
prices** of closed candles; output is a list of the same size, `NaN` for the
first `period` indices (warmup), then one RSI value per candle.

## Idea

RSI measures how much of the recent price movement was *up* versus *down*,
compressed to a 0–100 scale:

- **RSI ≈ 70+** — recent movement dominated by gains, conventionally "overbought"
- **RSI ≈ 30−** — dominated by losses, "oversold"
- **RSI = 50** — gains and losses in balance

```text
100 ┤                          ___
    │                     ____/   overbought
 70 ┤••••••••••••••••••••/••••••••••••••••••••
    │            __      /
 50 ┤        ___/  \____/
    │       /
 30 ┤••••••/•••••••••••••••••••••••••••••••••••
    │  ___/                       oversold
  0 ┤_/
    └──────────────────────────────────────────▶ time
```

## Algorithm

Each step compares consecutive closes and splits the change into a gain and a
loss component (one of the two is always zero):

```text
change_i = price_i − price_(i−1)
gain_i   = max(change_i, 0)
loss_i   = max(−change_i, 0)
```

### 1. Seed — plain average over the first `period` changes

```text
avgGain = (gain_1 + … + gain_14) / 14
avgLoss = (loss_1 + … + loss_14) / 14
```

The first RSI value lands at index `period` (index 14 for a 14-period RSI).

### 2. Wilder smoothing for every later candle

```text
avgGain_i = (avgGain_(i−1) × 13 + gain_i) / 14
avgLoss_i = (avgLoss_(i−1) × 13 + loss_i) / 14
```

This is an exponential moving average in disguise: each new value carries
weight `1/period`, the history carries `(period−1)/period`. It reacts slower
than a plain SMA and never fully "forgets" — which is why RSI values depend
slightly on how much history you feed them.

### 3. Convert to the 0–100 scale

```text
RS  = avgGain / avgLoss
RSI = 100 − 100 / (1 + RS)
```

```mermaid
flowchart LR
    P[closes] --> CH[per-candle change] --> GL[split gain / loss]
    GL --> SEED[seed: simple average\nover first 14 changes]
    SEED --> W[Wilder smoothing\navg = avg×13/14 + new/14]
    W --> RS[RS = avgGain / avgLoss] --> RSI[RSI = 100 − 100/(1+RS)]
```

## Edge cases (exactly as coded)

| Condition | Result | Why |
|---|---|---|
| `prices.size() <= period` | empty list | not even one full seed window |
| `avgLoss == 0` | `100.0` | only gains ⇒ RS would be ∞ |
| `avgGain == 0` | `0.0` | only losses |
| indices `0 .. period−1` | `NaN` | warmup padding |

The `NaN` padding convention matters downstream: the [Stochastic
RSI](stochastic-rsi.md) and the SMA-based K/D lines all propagate it, and
`IndicatorAnalyzer` uses it to find the first fully-valid row.

## Why Wilder and not a plain average?

A plain 14-candle average drops the oldest change abruptly, which makes RSI
jump when a large old move leaves the window ("drop-off effect"). Wilder's
recursive form decays old information smoothly, so the indicator is a function
of the *entire* history with exponentially fading weights:

```text
weight of a change n candles ago  ≈  (13/14)^n / 14

now ▏█████
−5  ▏███▌
−10 ▏██▌
−20 ▏█▎
−40 ▏▎          (never exactly zero)
```
