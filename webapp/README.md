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
- The selection lives in the URL (`?market=BTC-EUR&interval=1d&ind=rsi,macd&mode=any`),
  so a view can be bookmarked or reloaded.

## Buy rules (`src/signals.js`)

| Button | Fires when |
|---|---|
| RSI | RSI(14) ≤ 30 |
| S-RSI | %K crosses above %D with %K ≤ 0.2 (StochRSI 14/3/3) |
| MACD | MACD line crosses above the signal line (histogram ≤ 0 → > 0), 12/26/9 |

`src/indicators.js` is a 1:1 port of the Java `Indicators` class (same NaN
warmup conventions); on the same 199-candle window it reproduces the values in
the `data-bitvavo-local*.csv` reports exactly.

The dev server exposes the CSVs as JSON via `/api/markets` and
`/api/klines?market=…&interval=…` (see `vite.config.js`); no other backend is
needed. Refresh candles with `KlineHistoryImportBitvavo` and reload the page.
