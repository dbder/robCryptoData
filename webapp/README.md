# Crypto Charts (React)

Local candlestick viewer on top of the candle store in `../output/klines-bitvavo/`
(filled by `KlineHistoryImportBitvavo` / `CryptoAnalysisBitvavo`).

```
cd webapp
npm install      # first time only
npm run dev      # opens http://localhost:5173
```

- **Coin / timeframe** — every `<market>_<interval>.csv` in the store (1h … 1M).
- **RSI / S-RSI / MACD** buttons — each independently toggles an indicator pane
  under the candles and its buy rule. Candles at a buy signal turn **gold**.
- **Golden when** — `all fire` (every enabled indicator fires on the same
  candle) or `any fires`.
- **Trade simulation** — every buy signal opens a trade at that candle's close
  (only when no trade is open; signals arriving while one is open are skipped
  and shown with a gold outline). The trade sells when the low reaches the
  stop (default −8%) or the high reaches the target (default +25%); both are
  editable, as is the amount per trade (default €1000). Buy/sell arrows on the
  chart show each trade's € result, entry/stop/target lines mark the open
  trade, the footer sums realized P/L (plus the compounded % for reference)
  and *show log* lists every trade.
- The selection lives in the URL (`?market=BTC-EUR&interval=1d&ind=rsi,macd&mode=any&stop=8&target=25&stake=1000`),
  so a view can be bookmarked or reloaded.

## Buy rules (`src/signals.js`)

| Button | Fires when |
|---|---|
| RSI | RSI(14) ≤ 30 |
| S-RSI | %K crosses above %D with %K ≤ 0.2 (StochRSI 14/3/3) |
| MACD | MACD line crosses above the signal line (histogram ≤ 0 → > 0), 12/26/9 |

Simulation rules live in `src/trades.js`: the stop is checked before the
target within a candle (conservative), and a gap beyond a level fills at the
candle's open.

`src/indicators.js` is a 1:1 port of the Java `Indicators` class (same NaN
warmup conventions); on the same 199-candle window it reproduces the values in
the `data-bitvavo-local*.csv` reports exactly.

The dev server exposes the CSVs as JSON via `/api/markets` and
`/api/klines?market=…&interval=…` (see `vite.config.js`); no other backend is
needed. Refresh candles with `KlineHistoryImportBitvavo` and reload the page.
