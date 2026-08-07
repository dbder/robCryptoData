# Changelog

Hier houden we bij wat er verandert in het project.

## [3] - 2026-08-07

### Nieuw
- Nieuw startpunt `PredictionBacktest`: voorspelt per munt de richting van de volgende dag (logistische regressie, walk-forward geëvalueerd tegen de basislijnen "altijd omhoog" en "zelfde richting als gisteren"), inclusief een echte voorspelling voor morgen (`UP`/`DOWN` + kans). Resultaten in de console en `output/predictions_<datum>.csv`.
- Nieuw package `nl.debo.cryptodata.predict` met verwisselbare bouwstenen: `IndicatorFeatures`, `NextDayDirectionLabeler`, `Predictor`-implementaties en `WalkForwardBacktest`.
- Handelssimulatie met Bitvavo-kosten (standaardtarief; EUR 0,25% taker / 0,15% maker, USDC 0,05%, USDT 0,10%) in `TradingSimulation`: kolommen `NET_TAKER`/`NET_MAKER`/`BUY_HOLD`/`TRADES`.
- Vier extra features: ATR en Bollinger %B (nieuw in `Indicators`), afstand tot SMA50 en dagrange.
- `KlineCsvStore.readKlines` leest de candlestick-CSV's weer in.

## [2] - 2026-08-07

### Nieuw
- Nieuw startpunt `KlineHistoryImport`: bewaart de ruwe candlesticks in `output/klines/` (één CSV per symbool+interval). De eerste run haalt de volledige historie op vanaf de eerste Binance-candle; elke volgende run vult alleen nieuwe candles aan.

### Veranderd
- `output/klines/` is uitgezonderd van `.gitignore`, zodat de candlestick-historie mee de repo in kan.

## [1] - 2026-08-07

### Nieuw
- Range-kolommen in het XLSX-rapport voor handige filterbakjes: RSI in stappen van 10, StochRSI/K/D in stappen van 0.2, MACD/Signal/Hist als `Positive`/`Negative`/`Zero` (via nieuwe `...Range()`-methodes op `ResultRow`).

### Veranderd
- Rapporten (CSV en XLSX) komen nu in de map `output/` terecht; `output/` staat in `.gitignore` en oude gegenereerde databestanden zijn uit de repo gegooid.
- De voorwaardelijke opmaak in de XLSX is meeverhuisd met de nieuwe kolomindeling.

### Weggehaald
- De ODS-export (`OdsPrinter`): alleen XLSX en CSV blijven over.
