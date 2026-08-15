# Changelog

Hier houden we bij wat er verandert in het project.

## [14] - 2026-08-15

### Nieuw
- React-webapp in `webapp/` (Vite + lightweight-charts) die de lokale candle-opslag `output/klines-bitvavo/` als candlestick-grafiek toont: kies een munt en tijdvenster (1h t/m 1M); onder de grafiek staan de knoppen RSI, S-RSI en MACD die los van elkaar aan/uit kunnen. Elke knop toont het indicatorpaneel onder de candles en kleurt candles op een koopsignaal **goud** (RSI ≤ 30; StochRSI %K kruist %D omhoog met K ≤ 0,2; MACD-lijn kruist signaal omhoog). Met "all fire"/"any fires" bepaal je of alle ingeschakelde indicatoren tegelijk moeten vuren of één volstaat. Elke koopsignaal-candle opent een gesimuleerde trade van €1000 (instelbaar) op de slotkoers, mits er geen trade openstaat (overgeslagen signalen krijgen een gouden rand); de trade wordt verkocht zodra de koers 8% of meer zakt (stop) of 25% of meer stijgt (doel) — beide instelbaar. Koop/verkoop-pijlen tonen het resultaat in euro's per trade, entry/stop/doel-lijnen markeren de open trade, de voettekst telt de gerealiseerde winst/verlies op (met het samengestelde percentage ter referentie) en "show log" toont alle trades. De keuze staat in de URL zodat een weergave herladen kan worden. De indicatorberekening (`webapp/src/indicators.js`) is een 1-op-1 port van de Java `Indicators` en geeft op hetzelfde candlevenster exact de rapportwaardes. Starten: `cd webapp && npm install && npm run dev` (zie `webapp/README.md`).

## [13] - 2026-08-12

### Nieuw
- Nieuw startpunt `CryptoAnalysisBitvavoForDate`: de Bitvavo-analyse voor een zelfgekozen datum. De console vraagt jaar, maand en dag apart (Enter bij jaar = huidig jaar, twee cijfers mogen ook (`26` = 2026), en maand/dag zonder voorloopnul (`8` = 08)); een onmogelijke combinatie zoals 30 februari wordt gemeld en opnieuw gevraagd. De analyse draait daarna alsof hij aan het eind van die dag (UTC) is gestart: candles die later sloten zijn onzichtbaar, dus op dezelfde candle-opslag komt er exact uit wat een live run op die dag had opgeleverd — een week- of maandcandle die toen nog open stond, telt dan ook niet mee. Rapporten in `output/data-bitvavo-fordate<datum>.csv/.xlsx`; de eigen bestandsnaam voorkomt dat een teruggedateerde run aanvult op het `data-bitvavo-local`-rapport dat die dag écht is geschreven.

### Veranderd
- `LocalKlineSource` kent nu een optionele tijdshorizon (cutoff): alleen candles die vóór dat moment sloten worden geserveerd en het candlevenster wordt dáárna pas genomen, zodat de indicator-warmup terugschuift in de tijd in plaats van te krimpen. Zonder cutoff verandert er niets.
- `CryptoAnalysis` kan een rapportdatum meekrijgen die de datumstempel in de bestandsnamen en het XLSX bepaalt; zonder blijft het vandaag. De datum stempelt alleen de uitvoer — het "als-van-toen" rekenen is de taak van de `KlineSource` met cutoff.

## [12] - 2026-08-12

### Nieuw
- Algoritme-documentatie in `src/main/resources/nl/debo/cryptodata/`, met mermaid-diagrammen en ASCII-schetsen: `analysis-pipeline.md` (de hele `CryptoAnalysisBitvavo`-pijplijn, van lokale candle-opslag tot rapport, inclusief warmup-boekhouding en parametertabel), `rsi.md` (RSI met Wilder-smoothing), `stochastic-rsi.md` (StochRSI plus K/D-gladstrijking), `macd.md` (EMA, MACD-lijn, signaal en histogram) en `signal-normalization.md` (MADR, geschaald MACD-histogram en de drie normalisatiestrategieën).

### Weggehaald
- De nieuwsfunctie: `NewsClient`, de `news`-kolom in de CSV en het News-tabblad in het XLSX-rapport zijn verwijderd, net als de `baseAssetOf`-parameter van `CryptoAnalysis` (die bestond alleen voor de nieuws-lookup). Oudere CSV's met een nieuwskolom worden nog gewoon ingelezen; de nieuwskolom wordt daarbij genegeerd.

### Veranderd
- De rapportrijen worden vóór het wegschrijven (CSV en XLSX) gesorteerd: op symbool (alfabetisch), per symbool op de lengte van het tijdvenster aflopend (1M vóór 1W vóór 1d). Voorheen stonden de rijen in voltooiingsvolgorde van de parallelle verwerking.
- Niet-afgesloten candles kunnen nu nergens meer in de analyse belanden: `IndicatorAnalyzer.latestRow` filtert candles op sluittijd (`closeTime ≤ nu`) in plaats van blind de laatste candle van de reeks weg te gooien in de aanname dat die nog open is. Die aanname klopte niet altijd: bij een illiquide markt laat Bitvavo de open candle weg zolang die geen trades heeft, waardoor het API-pad juist de nieuwste gesloten candle verloor. Dat is nu ook opgelost.
- Doordat het filter op tijd werkt in plaats van op positie heeft `LocalKlineSource` de synthetische open candle niet meer nodig; die is weggehaald. De bron geeft nu simpelweg de nieuwste `limit − 1` gesloten candles terug — hetzelfde candlevenster dat een API-verzoek oplevert nadat zijn open candle is weggefilterd.

### Veranderd
- `LocalKlineSource` houdt de lokale candle-opslag nu zelf bij: geef hem een `KlineHistoryImporter` mee en elke aanvraag werkt eerst precies die ene markt/interval-combinatie bij — de CSV wordt bij het eerste gebruik aangemaakt en volledig gevuld, daarna alleen aangevuld met candles die sindsdien gesloten zijn, en als er nog niets nieuws gesloten kán zijn wordt de beurs helemaal niet bevraagd. Zonder importer blijft de bron read-only zoals voorheen.
- `CryptoAnalysisBitvavoFromLocal` draait daardoor niet meer eerst de volledige kline-import (alle markten × 9 intervallen), maar ververst on demand alleen de combinaties die de analyse echt gebruikt (symbolen × 1d/1W/1M). Voor het verversen van de hele opslag blijft `KlineHistoryImportBitvavo` het startpunt.
- Een markt zonder gesloten candles (bijv. het 1M-interval van een net genoteerde munt zoals QUID-EUR) is geen fout meer: de import maakt dan een CSV met alleen de kopregel aan (nieuw `KlineCsvStore.ensureCsv`, dat ook een leeg achtergebleven bestand van een afgebroken run repareert) en `LocalKlineSource` geeft nul candles terug — net als de API bij een markt zonder trades — zodat de pijplijn de rij stilletjes overslaat als "te weinig data".
- `LocalKlineSource.getKlinesAsync` draait op een eigen virtual thread, omdat het bijwerken kan blokkeren op netwerk- en rate-limit-pauzes.

## [10] - 2026-08-11

### Nieuw
- Nieuw startpunt `CryptoAnalysisBitvavoFromLocal`: draait eerst de kline-import en bouwt daarna de rapporten uit de lokale candle-CSV's in plaats van de live API, via de nieuwe `LocalKlineSource` (uitvoer in `output/data-bitvavo-local<datum>.csv/.xlsx`). Omdat de pijplijn de laatste candle als "nog open" weggooit en de lokale opslag alleen gesloten candles bevat, plakt `LocalKlineSource` één synthetische open candle achteraan; zo blijft het candlevenster identiek aan het API-pad. Vergelijking van beide paden op dezelfde dag: 965 van 973 rijen exact gelijk; de 8 verschillen zitten bij illiquide markten (waar het API-pad de nieuwste gesloten candle verliest zolang de huidige candle nog geen trades heeft — het lokale pad is daar juist actueler) en bij HNT-EUR, waar Bitvavo zijn eigen historie heeft herzien (candles verwijderd en waardes aangepast t.o.v. de lokale opslag).

## [9] - 2026-08-11

### Weggehaald
- Alle Binance-code — we gaan verder met alleen Bitvavo. Weg zijn `CryptoAnalysisBinance`, `KlineHistoryImport` (de Binance-kline-import), `BinanceClient` en de resources `symbols` en `notavailablesymbols`. De standaard main class van de jar is nu `CryptoAnalysisBitvavo`.

### Veranderd
- `PredictionBacktest` draait nu op de Bitvavo-data: symbolen uit `symbols-bitvavo` en dagcandles uit `output/klines-bitvavo/` (het rekende al met Bitvavo-fees). Geverifieerd: 344 markten geëvalueerd, 95 overgeslagen wegens te weinig historie.
- `TestXslxPrinter` leest standaard het Bitvavo-rapport van vandaag (`output/data-bitvavo<datum>.csv`).
- `PairSymbols` blijft beide symboolformaten begrijpen: het aaneengeschreven formaat (`SOLEUR`) komt nog voor in oude rapport-CSV's uit de verwijderde Binance-pijplijn.

## [8] - 2026-08-10

### Nieuw
- `PrivateInfoImportBitvavo` toont per munt de aankoopprijs en de winst/het verlies in euro's: nieuwe kolommen AVG BUY (gewogen gemiddelde koopprijs), COST EUR (wat de huidige positie gekost heeft, inclusief handelskosten) en P/L EUR (verschil met de waarde van nu, groen/rood). De transactiehistorie komt per gehouden munt uit `GET /v2/trades` (nieuw in `BitvavoPrivateClient`) en wordt samengevoegd in een blijvend grootboek `output/balance-bitvavo/trades.csv` (nieuw `TradeCsvStore`), zodat de kostprijs bewaard blijft als transacties uit het 500-per-markt-API-venster vallen.
- `CryptoAnalysis` heeft een Begin-kolom: de startdatum van de candle-periode in compacte Nederlandse vorm (bijv. `zo 9 aug '26`, weekcandle `ma 3 aug '26`), via het nieuwe `DutchDate` (nl-NL, UTC zodat de datum bij de candlegrenzen van de beurs past). Zichtbaar in de console, de CSV (kolom `begin` tussen `interval` en `time`) en het XLSX-rapport (kolom Begin tussen Interval en Time; de rood/groen-opmaak is een kolom meegeschoven).

### Veranderd
- De kolommen CHANGE en P/L EUR in het saldo-overzicht zijn breder en lijnen nu strak uit: tekst wordt eerst op kolombreedte uitgelijnd en daarna pas gekleurd, zodat de onzichtbare kleurcodes de uitlijning niet meer breken. Prijzen onder € 1 krijgen vier decimalen (bijv. VET `0.0041` in plaats van `0.00`).
- Oudere analyse-CSV's (zonder `begin`-kolom) worden nog steeds ingelezen; het formaat wordt per regel herkend door te parsen, ook als een nieuwskop komma's bevat.
- De CSV-lezers van snapshots, transfers en trades slaan header- en lege regels nu overal in het bestand over (handmatig bewerken kan ze verplaatsen) in plaats van alleen de eerste regel als header te behandelen.

## [7] - 2026-08-10

### Nieuw
- `PrivateInfoImportBitvavo` houdt EUR-stortingen en -opnames (van/naar externe rekening) bij in een blijvend grootboek (`output/balance-bitvavo/eur-transfers.csv`, nieuw `TransferCsvStore`) en toont "winst sinds start": de eerste snapshot geldt als startsaldo, latere EUR-transfers worden opgesomd en verrekend zodat inleg niet als winst meetelt.

## [6] - 2026-08-09

### Nieuw
- Nieuw startpunt `PrivateInfoImportBitvavo`: het eigen Bitvavo-saldo gewaardeerd in EUR, met per asset het portfolio-aandeel en de verandering t.o.v. de vorige run, 24h/7d/30d-portfoliostatistieken en netto-inleg + totale winst/verlies uit de EUR-stortings- en opnamehistorie. Elke run schrijft een snapshot naar `output/balance-bitvavo/snapshots.csv`, waar de historiekolommen uit groeien. Vereist een `bitvavo.properties` (gitignored, read-only API-key volstaat) — kopieer `bitvavo.properties.temp` als startpunt.
- Nieuwe bouwstenen voor de privé-API: `BitvavoCredentials` (leest het properties-bestand), `BitvavoAuth` (HMAC-SHA256-handtekening), `BitvavoPrivateClient` (saldo, stortingen, opnames) en `BalanceCsvStore` (snapshot-CSV). De publieke `BitvavoClient` kan nu ook alle marktprijzen in één call ophalen (`getTickerPricesAsync`), en `JsonHttp` ondersteunt extra request-headers die per poging opnieuw worden berekend zodat handtekeningen vers blijven bij rate-limit-retries.
- Analyserapport per munt in `output/coinanalysis/` (428 stuks): overzicht, tijdlijn, fundamentals via CoinPaprika en prijshistorie uit de lokale Bitvavo-dagcandles.
- Kleur in de console via het nieuwe `ConsoleColor`: groen voor start/klaar-meldingen en positieve veranderingen, rood voor negatieve veranderingen, oranje voor waarschuwingen (rate limits, retries, overgeslagen symbolen) — gebruikt in de analyse, beide kline-imports, de rate limiter en de XLSX-schrijver.

### Veranderd
- De Bitvavo-kline-import haalt naast 1d/1W/1M nu ook de intraday-intervallen 1h, 2h, 4h, 6h, 8h en 12h op, en slaat het verzoek helemaal over als de eerstvolgende candle nog niet gesloten kan zijn (nieuw `KlineCsvStore.lastSavedCloseTime` plus een publieke `BitvavoClient.closeTime`): "up to date, no request needed".
- `output/klines-bitvavo/` en `output/coinanalysis/` gaan mee de repo in (uitzonderingen in `.gitignore`); `bitvavo.properties` met de API-sleutel is juist toegevoegd aan `.gitignore`.

## [5] - 2026-08-09

### Nieuw
- Bitvavo naast Binance: nieuw startpunt `CryptoAnalysisBitvavo`, met de gedeelde pijplijn in `CryptoAnalysis` (client via de nieuwe interface `KlineSource`, symbolenbestand, intervallen en uitvoernaam per beurs). Uitvoer in `output/data-bitvavo<datum>.csv/.xlsx`.
- `symbols-bitvavo` met alle 439 Bitvavo-markten (EUR en USDC) uit `GET /v2/markets`.
- Kolom "EUR/USD" in het XLSX-rapport: de noteringsvaluta van het paar, afgeleid met het nieuwe `PairSymbols` dat beide symboolformaten begrijpt (`SOL-EUR` én `SOLEUR`).
- Bitvavo-rate-limiting (limiet: 1000 punten/min per IP, overschrijding = 15 min IP-ban): `BitvavoRateLimiter` houdt verzoeken 65 ms uit elkaar, synchroniseert het budget met de `bitvavo-ratelimit-*`-headers en pauzeert tot het venster reset als het budget opraakt; bij een 429 wacht `JsonHttp` tot de ban afloopt en probeert opnieuw. Resterende punten verschijnen periodiek in de console.
- Nieuw startpunt `KlineHistoryImportBitvavo`: candlestick-historie voor alle Bitvavo-markten in `output/klines-bitvavo/`, zoals `KlineHistoryImport` voor Binance. Bitvavo pagineert achterstevoren (`limit` houdt de nieuwste candles), dus de import loopt met `start`/`end`-vensters terug in de tijd; `BitvavoClient` berekent maandcandle-sluittijden nu exact op de kalender.

### Veranderd
- Nieuws staat op een eigen tabblad "News" (één regel per munt) in plaats van als kolom in het resultatenblad.
- Het 1h-interval is weggehaald: de rapporten dekken nu 1d, 1w/1W en 1M.

## [4] - 2026-08-07

### Nieuw
- MADR-statistiek (Moving Average Distance Ratio) in het rapport: hoe ver de koers van zijn SMA50 af staat, geschaald naar 0..1 waarbij 0 = koop en 1 = verkoop (0.5 = op het gemiddelde). De ruwe afstand komt uit het nieuwe `Indicators.maDistanceRatio`.
- Genormaliseerde MACD-statistiek (`macdStat`): het MACD-histogram gedeeld door de koers (nieuw `Indicators.scaledMacdHistogram`), geschaald naar 0..1 en omgedraaid zodat 0 = bullish momentum (koop) en 1 = bearish momentum (verkoop); 0.5 betekent een crossover.
- Verwisselbare normalisatiestrategieën via de nieuwe interface `SignalNormalizer`: `ZScoreNormalizer` (rollende standaarddeviatie + logistische curve, de standaard), `StochasticNormalizer` (min/max-positie binnen het venster) en `ClampNormalizer` (vaste band). `IndicatorAnalyzer` krijgt er twee mee, zodat MADR en MACD los van elkaar te experimenteren zijn.
- Score-kolom in het XLSX-rapport: het gemiddelde van alle zes 0..1-statistieken (RSI/100, StochRSI, K, D, MADR, MACD-stat) als `score()` op `ResultRow`; rood vanaf 0.7, groen tot 0.3.

### Veranderd
- CSV krijgt de kolommen `madr` en `macdStat`; oudere bestanden (zonder deze kolommen) worden nog steeds ingelezen, ontbrekende waarden vallen terug op neutraal 0.5.
- XLSX krijgt de kolommen MADR, MACD Stat en Score met bijbehorende Range-filterbakjes en rood/groen-opmaak (≥0.8 / ≤0.2, voor Score 0.7/0.3); de consoleregel toont MADR en MACDstat.
- Bij te weinig historie voor de normalisatie (SMA-venster plus normalisatievenster, vooral bij 1M-candles van jonge munten) vallen MADR en MACD-stat terug op neutraal 0.5 in plaats van de rij te laten vervallen.

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
