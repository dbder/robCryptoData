# Crypto Charts (React)

Local candlestick viewer on top of the candle store in `../output/klines-bitvavo/`
(filled by `KlineHistoryImportBitvavo` / `CryptoAnalysisBitvavo`).

```
cd webapp
npm install      # first time only
npm run dev      # opens http://localhost:5173
```

- **Coin / timeframe** — every `<market>_<interval>.csv` in the store (1h … 1M).
  Coins you actually traded (ledger `../output/balance-bitvavo/trades.csv`, written by
  `PrivateInfoImportBitvavo`) are listed first, most recent transaction on top, marked ★.
- **★ my N trades** — your real buys (blue arrow below the bar) and sells (purple arrow above)
  from that ledger on the chart, with the euro amount; several fills in one candle are summed
  ("3 buys €2,990"). The checkbox next to the coin hides them (`mine=0` in the URL).
  The store only holds closed candles, so a trade newer than the store (today's, or since the
  last import) gets a **grey pending candle** in its own time slot, built from the trade
  prices; it disappears once the real candle is imported.
- **RSI / S-RSI / MACD** buttons — each independently toggles an indicator pane
  under the candles and its buy rule. Candles at a buy signal turn **gold**.
- **Golden when** — `all fire` (every enabled indicator fires on the same
  candle) or `any fires`.
- **Trade simulation** — every buy signal opens a trade at that candle's close
  (with **skip signal if trade is active** checked — the default — only when no
  trade is open; skipped signals get a gold outline. Unchecked, every signal
  opens its own trade with its own stop/target). Every later candle is judged at its close:
  closes at or below −8% (stop) or at or above +25% (target) sell **at that
  close**, so a candle that closes −10% books −10%. A 0.25% fee is charged on
  the buy and again on the sell. Thresholds, amount per trade (default €1000)
  and fee are editable. Buy/sell arrows on the chart show each trade's € result
  (fees included), entry/stop/target lines mark the open trade, the footer sums
  realized P/L (plus the compounded % for reference) and *show log* lists every
  trade.
- **Σ all coins** — runs the current configuration on every coin that has candles for the
  timeframe. The coins are grouped (Stablecoins, Alpha, L1 / L2 & infra, DeFi, AI / DePIN,
  Gaming / NFT, Meme, Dead, Other — `src/categories.js`, by base asset so BTC-EUR and BTC-USDC
  share a group); a table at the top shows the totals per group and clicking a group (or its
  checkbox) excludes/includes it; the header has a select-all checkbox and `all` / `none` buttons. The coin list and
  its totals only cover the included groups. The select in a coin's row moves it to another
  group; moves and excluded groups are remembered in the browser (localStorage), `reset`
  undoes the moves.
- The selection lives in the URL (`?market=BTC-EUR&interval=1d&ind=rsi,macd&mode=any&stop=8&target=25&stake=1000&fee=0.25&skip=1`),
  so a view can be bookmarked or reloaded.

## Buy rules (`src/signals.js`)

| Button | Fires when |
|---|---|
| RSI | RSI(14) ≤ 30 |
| S-RSI | %K crosses above %D with %K ≤ 0.2 (StochRSI 14/3/3) |
| MACD | MACD line crosses above the signal line (histogram ≤ 0 → > 0), 12/26/9 |

Simulation rules live in `src/trades.js`; € result of a trade =
`stake × (1 − fee) × exit/entry × (1 − fee) − stake`.

`src/indicators.js` is a 1:1 port of the Java `Indicators` class (same NaN
warmup conventions); on the same 199-candle window it reproduces the values in
the `data-bitvavo-local*.csv` reports exactly.

The dev server exposes the CSVs as JSON via `/api/markets`,
`/api/klines?market=…&interval=…` and `/api/trades` (see `vite.config.js`); no other backend is
needed. Refresh candles with `KlineHistoryImportBitvavo` and reload the page.
