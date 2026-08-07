# Changelog

Hier houden we bij wat er verandert in het project.

## [2] - 2026-08-07

### Nieuw
- Nieuw startpunt `KlineHistoryImport` dat de ruwe candlesticks bewaart in `output/klines/` (één CSV per symbool+interval, bijv. `BTCEUR_1h.csv`). De eerste run haalt de volledige historie op vanaf de eerste candle van de munt op Binance; elke volgende run vult alleen de nieuwe candles aan. Gewoon vanuit IntelliJ starten; de jar zelf start nog steeds `CryptoAnalysis`.

### Veranderd
- `output/klines/` is uitgezonderd van `.gitignore`, zodat de candlestick-historie mee de repo in kan.

## [1] - 2026-08-07

### Nieuw
- Range-kolommen in het XLSX-rapport, zodat je in de filterdropdowns een paar handige bakjes krijgt in plaats van een eindeloze lijst losse waardes:
  - **RSI Range** — stappen van 10 (bijv. `60-70`)
  - **StochRSI / K / D Range** — stappen van 0.2 (bijv. `0.2-0.4`)
  - **MACD / Signal / Hist Range** — `Positive`, `Negative` of `Zero`
- `ResultRow` maakt die labels zelf via nieuwe methodes: `rsiRange()`, `stochRsiRange()`, `kRange()`, `dRange()`, `macdRange()`, `macdSignalRange()` en `macdHistogramRange()`.

### Veranderd
- Rapporten (CSV en XLSX) komen nu netjes in de map `output/` terecht in plaats van los in de hoofdmap.
- De voorwaardelijke opmaak in de XLSX is meeverhuisd met de nieuwe kolomindeling (StochRSI zit nu in kolom G, MACD-histogram in kolom Q).
- `output/` staat nu in `.gitignore`, en de oude gegenereerde databestanden (`data*.csv`, `data*.ods`, `data*.xlsx`) zijn uit de repo gegooid.

### Weggehaald
- De ODS-export is eruit: `OdsPrinter` is verwijderd en er wordt geen `.ods`-bestand meer gemaakt — alleen XLSX en CSV. 
