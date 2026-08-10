package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BitvavoClient;
import nl.debo.cryptodata.tools.BitvavoCredentials;
import nl.debo.cryptodata.tools.BitvavoPrivateClient;
import nl.debo.cryptodata.tools.BitvavoPrivateClient.AssetBalance;
import nl.debo.cryptodata.tools.BitvavoPrivateClient.Trade;
import nl.debo.cryptodata.tools.BitvavoPrivateClient.Transfer;
import nl.debo.cryptodata.tools.CostBasisCalculator;
import nl.debo.cryptodata.tools.CostBasisCalculator.Basis;
import nl.debo.cryptodata.utils.BalanceCsvStore;
import nl.debo.cryptodata.utils.BalanceCsvStore.SnapshotRow;
import nl.debo.cryptodata.utils.ConsoleColor;
import nl.debo.cryptodata.utils.FileUtil;
import nl.debo.cryptodata.utils.TradeCsvStore;
import nl.debo.cryptodata.utils.TradeCsvStore.TradeRow;
import nl.debo.cryptodata.utils.TransferCsvStore;
import nl.debo.cryptodata.utils.TransferCsvStore.TransferRow;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Entry point: prints the Bitvavo account balance valued in EUR, with history
 * and stats. Every run appends a snapshot (one row per held asset) to
 * {@code output/balance-bitvavo/snapshots.csv}; the accumulated snapshots
 * drive the change columns and the 24h/7d/30d portfolio stats, so those fill
 * in as the tool is run over time. Net invested and overall P/L are computed
 * from the account's EUR deposit and withdrawal history.
 *
 * <p>EUR deposits and withdrawals — the fiat flows from and to an external
 * source such as the bank account — are merged into a persistent ledger
 * ({@code output/balance-bitvavo/eur-transfers.csv}) so they stay on record
 * even after they age out of the API's 500-entry history window. Executed
 * trades are merged into their own ledger
 * ({@code output/balance-bitvavo/trades.csv}) the same way; from it each
 * held coin gets a weighted-average buy price, its EUR cost and the profit
 * or loss against today's value. The first
 * snapshot ever taken acts as the starting balance; EUR transfers after that
 * moment are listed and netted out, giving a profit-since-start figure that
 * is not distorted by money moved in or out.</p>
 *
 * <p>Requires a {@code bitvavo.properties} file (gitignored, read-only API
 * key suffices) — see {@link BitvavoCredentials}.
 *
 * <p>Run directly from the IDE; the jar's default main class remains
 * {@link CryptoAnalysisBinance}.</p>
 */
public final class PrivateInfoImportBitvavo {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    /** Sentinel price for assets without a EUR market. */
    private static final double UNPRICED = -1;

    /** Right-aligned widths of the two colored table columns. */
    private static final int CHANGE_WIDTH = 12;
    private static final int PROFIT_LOSS_WIDTH = 18;

    private PrivateInfoImportBitvavo() {
    }

    public static void main(String[] args) throws Exception {
        BitvavoCredentials credentials;
        try {
            credentials = BitvavoCredentials.load();
        } catch (IOException e) {
            System.err.println(ConsoleColor.orange(e.getMessage()));
            return;
        }

        long now = System.currentTimeMillis();
        Path csvPath = FileUtil.applicationDir().resolve("output/balance-bitvavo/snapshots.csv");
        System.out.println(ConsoleColor.green(
                "Bitvavo balance report — " + TIME_FORMAT.format(Instant.ofEpochMilli(now))));

        var publicClient = new BitvavoClient();
        var privateClient = new BitvavoPrivateClient(credentials);

        var balancesFuture = privateClient.getBalanceAsync();
        var pricesFuture = publicClient.getTickerPricesAsync();
        var depositsFuture = privateClient.getDepositHistoryAsync();
        var withdrawalsFuture = privateClient.getWithdrawalHistoryAsync();

        List<AssetBalance> balances;
        Map<String, Double> prices;
        List<Transfer> deposits;
        List<Transfer> withdrawals;
        try {
            balances = balancesFuture.join();
            prices = pricesFuture.join();
            deposits = depositsFuture.join();
            withdrawals = withdrawalsFuture.join();
        } catch (java.util.concurrent.CompletionException e) {
            // Expected setup problems (unconfirmed key, revoked key, wrong
            // secret) come back as HTTP 403 with a clear Bitvavo message.
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            System.err.println(ConsoleColor.orange(message));
            return;
        }

        List<SnapshotRow> rows = toSnapshotRows(now, balances, prices);
        List<SnapshotRow> history = BalanceCsvStore.readAll(csvPath);
        BalanceCsvStore.append(csvPath, rows);

        Path transfersPath =
                FileUtil.applicationDir().resolve("output/balance-bitvavo/eur-transfers.csv");
        List<TransferRow> ledger =
                TransferCsvStore.merge(transfersPath, toEurTransferRows(deposits, withdrawals));

        Path tradesPath = FileUtil.applicationDir().resolve("output/balance-bitvavo/trades.csv");
        List<TradeRow> tradeLedger =
                TradeCsvStore.merge(tradesPath, fetchTrades(privateClient, balances, prices));
        Map<String, Basis> bases = CostBasisCalculator.fromTrades(tradeLedger);

        printBalanceTable(rows, history, bases);
        warnBasisGaps(rows, bases);
        printHistoryStats(now, totalValue(rows), history);
        printProfitAndLoss(totalValue(rows), deposits, withdrawals);
        printSinceStart(totalValue(rows), history, ledger);

        System.out.println(ConsoleColor.green("Snapshot appended to " + csvPath));
        System.out.println(ConsoleColor.green("Transfer ledger updated at " + transfersPath));
        System.out.println(ConsoleColor.green("Trade ledger updated at " + tradesPath));
    }

    /**
     * Values each held asset in EUR via its {@code <ASSET>-EUR} market; EUR
     * itself is worth 1. Assets without a EUR market stay in the snapshot
     * with value 0 so they remain visible in the history.
     */
    private static List<SnapshotRow> toSnapshotRows(
            long timestamp,
            List<AssetBalance> balances,
            Map<String, Double> prices
    ) {
        var rows = new ArrayList<SnapshotRow>();
        for (AssetBalance balance : balances) {
            if (balance.total() <= 0) {
                continue;
            }
            double price;
            if (balance.symbol().equals("EUR")) {
                price = 1.0;
            } else {
                Double market = prices.get(balance.symbol() + "-EUR");
                if (market == null) {
                    System.err.println(ConsoleColor.orange(
                            "No EUR market for " + balance.symbol() + " — valued at 0"));
                    price = UNPRICED;
                } else {
                    price = market;
                }
            }
            double value = price > 0 ? balance.total() * price : 0;
            rows.add(new SnapshotRow(timestamp, balance.symbol(),
                    balance.available(), balance.inOrder(), price, value));
        }
        rows.sort(Comparator.comparingDouble(SnapshotRow::valueEur).reversed());
        return rows;
    }

    private static double totalValue(List<SnapshotRow> rows) {
        return rows.stream().mapToDouble(SnapshotRow::valueEur).sum();
    }

    /** Sum of each snapshot's value, keyed by snapshot timestamp, oldest first. */
    private static TreeMap<Long, Double> totalsByTimestamp(List<SnapshotRow> history) {
        var totals = new TreeMap<Long, Double>();
        for (SnapshotRow row : history) {
            totals.merge(row.timestamp(), row.valueEur(), Double::sum);
        }
        return totals;
    }

    /**
     * Fetches trade history for every held asset with a EUR market, in
     * parallel. A market whose fetch fails is reported and skipped so one
     * bad market cannot take down the report.
     */
    private static List<TradeRow> fetchTrades(
            BitvavoPrivateClient client,
            List<AssetBalance> balances,
            Map<String, Double> prices
    ) {
        var futures = new LinkedHashMap<String, CompletableFuture<List<Trade>>>();
        for (AssetBalance balance : balances) {
            String market = balance.symbol() + "-EUR";
            if (balance.total() <= 0 || balance.symbol().equals("EUR")
                    || !prices.containsKey(market)) {
                continue;
            }
            futures.put(market, client.getTradeHistoryAsync(market));
        }
        var rows = new ArrayList<TradeRow>();
        for (var entry : futures.entrySet()) {
            List<Trade> trades;
            try {
                trades = entry.getValue().join();
            } catch (CompletionException e) {
                String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
                System.err.println(ConsoleColor.orange(
                        "Trade history for " + entry.getKey() + " failed: " + message));
                continue;
            }
            if (trades.size() == BitvavoPrivateClient.HISTORY_LIMIT) {
                System.err.println(ConsoleColor.orange("trades for " + entry.getKey()
                        + " returned " + BitvavoPrivateClient.HISTORY_LIMIT
                        + " entries — older trades may be missing, cost basis may be incomplete"));
            }
            for (Trade t : trades) {
                rows.add(new TradeRow(t.timestamp(), t.market(), t.id(), t.side(),
                        t.amount(), t.price(), t.fee(), t.feeCurrency()));
            }
        }
        return rows;
    }

    private static void printBalanceTable(
            List<SnapshotRow> rows,
            List<SnapshotRow> history,
            Map<String, Basis> bases
    ) {
        var totals = totalsByTimestamp(history);
        boolean hasHistory = !totals.isEmpty();
        Map<String, Double> previousValues = new HashMap<>();
        if (hasHistory) {
            long previousTimestamp = totals.lastKey();
            for (SnapshotRow row : history) {
                if (row.timestamp() == previousTimestamp) {
                    previousValues.put(row.asset(), row.valueEur());
                }
            }
        }

        double total = totalValue(rows);
        double totalCost = 0;
        double totalProfit = 0;
        System.out.println();
        System.out.printf(Locale.US, "%-8s %16s %14s %12s %12s %12s %14s %7s %12s %18s%n",
                "ASSET", "AVAILABLE", "IN ORDER", "PRICE EUR", "AVG BUY", "COST EUR",
                "VALUE EUR", "PORT%", "CHANGE", "P/L EUR");
        for (SnapshotRow row : rows) {
            String price = row.priceEur() == UNPRICED ? "n/a" : formatPrice(row.priceEur());
            double share = total > 0 ? 100 * row.valueEur() / total : 0;
            String avgBuy = "n/a";
            String cost = "n/a";
            String profitLoss = padColored("n/a", PROFIT_LOSS_WIDTH, 0);
            Basis basis = bases.get(row.asset());
            if (!row.asset().equals("EUR") && basis != null && basis.amount() > 0) {
                avgBuy = formatPrice(basis.avgPrice());
                // Coins the ledger cannot account for (transferred in or
                // earned, see warnBasisGaps) carry no cost on record; cost
                // and P/L cover the bought part only.
                double covered = Math.min(basis.amount(), row.available() + row.inOrder());
                double coveredCost = basis.costEur() * covered / basis.amount();
                cost = String.format(Locale.US, "%,.2f", coveredCost);
                if (row.priceEur() != UNPRICED && coveredCost > 0) {
                    double profit = covered * row.priceEur() - coveredCost;
                    totalCost += coveredCost;
                    totalProfit += profit;
                    profitLoss = padColored(String.format(Locale.US, "%+,.2f (%+.1f%%)",
                            profit, 100 * profit / coveredCost), PROFIT_LOSS_WIDTH, profit);
                }
            }
            System.out.printf(Locale.US, "%-8s %16.8f %14.8f %12s %12s %12s %14s %6.1f%% %s %s%n",
                    row.asset(), row.available(), row.inOrder(), price, avgBuy, cost,
                    String.format(Locale.US, "%,.2f", row.valueEur()), share,
                    changeLabel(hasHistory, previousValues.get(row.asset()), row.valueEur()),
                    profitLoss);
        }
        String totalProfitLoss = totalCost > 0
                ? padColored(String.format(Locale.US, "%+,.2f (%+.1f%%)",
                        totalProfit, 100 * totalProfit / totalCost), PROFIT_LOSS_WIDTH, totalProfit)
                : padColored("n/a", PROFIT_LOSS_WIDTH, 0);
        System.out.printf(Locale.US, "%-8s %16s %14s %12s %12s %12s %14s %6.1f%% %12s %s%n",
                "TOTAL", "", "", "", "",
                String.format(Locale.US, "%,.2f", totalCost),
                String.format(Locale.US, "%,.2f", total),
                rows.isEmpty() ? 0.0 : 100.0, "", totalProfitLoss);
    }

    /** Two decimals for prices from 1 EUR up, four below (sub-cent coins). */
    private static String formatPrice(double price) {
        return String.format(Locale.US, price >= 1 ? "%,.2f" : "%.4f", price);
    }

    /**
     * A held amount the trade ledger cannot account for (transfers in or
     * out, staking rewards, trades older than the ledger) makes that asset's
     * cost basis incomplete — flag it rather than show silently wrong
     * numbers.
     */
    private static void warnBasisGaps(List<SnapshotRow> rows, Map<String, Basis> bases) {
        for (SnapshotRow row : rows) {
            if (row.asset().equals("EUR")) {
                continue;
            }
            double held = row.available() + row.inOrder();
            Basis basis = bases.get(row.asset());
            double tracked = basis == null ? 0 : basis.amount();
            if (held > 0 && Math.abs(tracked - held) / held > 0.01) {
                String detail = tracked < held
                        ? "the rest was transferred in or earned; cost covers the bought part only"
                        : "the difference was transferred out; cost is scaled to what is still held";
                System.err.println(ConsoleColor.orange(String.format(Locale.US,
                        "%s: trade ledger accounts for %.8f, %.8f held — %s",
                        row.asset(), tracked, held, detail)));
            }
        }
    }

    /**
     * Percent change against the same asset in the previous snapshot;
     * {@code n/a} on the first run ever, {@code new} for a first-seen asset.
     * Padded to {@link #CHANGE_WIDTH} before coloring.
     */
    private static String changeLabel(boolean hasHistory, Double previous, double current) {
        if (!hasHistory || (previous != null && previous == 0)) {
            return padColored("n/a", CHANGE_WIDTH, 0);
        }
        if (previous == null) {
            return padColored("new", CHANGE_WIDTH, 0);
        }
        double percent = 100 * (current - previous) / previous;
        return padColored(String.format(Locale.US, "%+.1f%%", percent), CHANGE_WIDTH, percent);
    }

    /**
     * Right-aligns {@code text} to {@code width} before coloring it, so the
     * invisible color codes cannot break the column alignment.
     */
    private static String padColored(String text, int width, double signal) {
        return colored(String.format(Locale.US, "%" + width + "s", text), signal);
    }

    private static void printHistoryStats(long now, double total, List<SnapshotRow> history) {
        var totals = totalsByTimestamp(history);
        System.out.println();
        System.out.println("Portfolio value history");
        if (totals.isEmpty()) {
            System.out.println("  n/a — first snapshot; stats fill in on later runs");
            return;
        }

        var last = totals.lastEntry();
        printStat("since last run (" + TIME_FORMAT.format(Instant.ofEpochMilli(last.getKey())) + ")",
                last.getValue(), total);
        printLookback(totals, now, total, "24h", Duration.ofDays(1));
        printLookback(totals, now, total, "7d", Duration.ofDays(7));
        printLookback(totals, now, total, "30d", Duration.ofDays(30));
    }

    /** Compares against the newest snapshot at least {@code period} old. */
    private static void printLookback(
            TreeMap<Long, Double> totals,
            long now,
            double total,
            String label,
            Duration period
    ) {
        var entry = totals.floorEntry(now - period.toMillis());
        if (entry == null) {
            System.out.printf("  %-28s n/a (no snapshot old enough)%n", label + ":");
        } else {
            printStat(label, entry.getValue(), total);
        }
    }

    private static void printStat(String label, double then, double current) {
        double delta = current - then;
        String text = String.format(Locale.US, "%+,.2f EUR   %s", delta,
                then > 0 ? String.format(Locale.US, "%+.1f%%", 100 * delta / then) : "n/a");
        System.out.printf("  %-28s %s%n", label + ":", colored(text, delta));
    }

    private static void printProfitAndLoss(
            double total,
            List<Transfer> deposits,
            List<Transfer> withdrawals
    ) {
        double depositedEur = deposits.stream()
                .filter(t -> t.symbol().equals("EUR"))
                .mapToDouble(Transfer::amount)
                .sum();
        // A withdrawal's fee also left the account; cancelled ones never did.
        double withdrawnEur = withdrawals.stream()
                .filter(t -> t.symbol().equals("EUR"))
                .filter(t -> !t.status().contains("cancel"))
                .mapToDouble(t -> t.amount() + t.fee())
                .sum();
        double netInvested = depositedEur - withdrawnEur;

        System.out.println();
        System.out.println("Invested & P/L");
        System.out.printf(Locale.US, "  %-28s %,.2f EUR%n", "EUR deposited:", depositedEur);
        System.out.printf(Locale.US, "  %-28s %,.2f EUR%n", "EUR withdrawn:", withdrawnEur);
        System.out.printf(Locale.US, "  %-28s %,.2f EUR%n", "Net invested:", netInvested);
        double profit = total - netInvested;
        String percent = netInvested > 0
                ? String.format(Locale.US, "%+.1f%%", 100 * profit / netInvested)
                : "n/a";
        System.out.printf("  %-28s %s%n", "P/L:",
                colored(String.format(Locale.US, "%+,.2f EUR   %s", profit, percent), profit));

        warnIgnoredCrypto(deposits, "deposits");
        warnIgnoredCrypto(withdrawals, "withdrawals");
        warnTruncated(deposits, "depositHistory");
        warnTruncated(withdrawals, "withdrawalHistory");
    }

    /**
     * Only EUR transfers make it into the ledger: those are the fiat flows
     * from and to an external source (the bank account). Crypto deposits and
     * withdrawals are out of scope here.
     */
    private static List<TransferRow> toEurTransferRows(
            List<Transfer> deposits,
            List<Transfer> withdrawals
    ) {
        var rows = new ArrayList<TransferRow>();
        for (Transfer t : deposits) {
            if (t.symbol().equals("EUR")) {
                rows.add(new TransferRow(t.timestamp(), "deposit",
                        t.symbol(), t.amount(), t.fee(), t.status()));
            }
        }
        for (Transfer t : withdrawals) {
            if (t.symbol().equals("EUR")) {
                rows.add(new TransferRow(t.timestamp(), "withdrawal",
                        t.symbol(), t.amount(), t.fee(), t.status()));
            }
        }
        return rows;
    }

    /**
     * Profit measured from the first snapshot ever taken (the starting
     * balance). EUR moved in or out after that moment is listed and netted
     * out of the profit, so a deposit does not show up as gains and a
     * withdrawal not as losses. Transfers from before the starting snapshot
     * are already part of the starting balance and are skipped.
     */
    private static void printSinceStart(
            double total,
            List<SnapshotRow> history,
            List<TransferRow> ledger
    ) {
        System.out.println();
        System.out.println("Profit since tracking start");
        var totals = totalsByTimestamp(history);
        if (totals.isEmpty()) {
            System.out.printf(Locale.US,
                    "  starting balance set to %,.2f EUR — profit fills in on later runs%n", total);
            return;
        }

        long startTimestamp = totals.firstKey();
        double startBalance = totals.firstEntry().getValue();
        System.out.printf(Locale.US, "  %-28s %,.2f EUR%n",
                "start (" + TIME_FORMAT.format(Instant.ofEpochMilli(startTimestamp)) + "):",
                startBalance);

        double netFlow = 0;
        int flowCount = 0;
        for (TransferRow t : ledger) {
            if (t.timestamp() <= startTimestamp || t.status().contains("cancel")) {
                continue;
            }
            // A withdrawal's fee also left the account.
            double signed = t.type().equals("deposit")
                    ? t.amount()
                    : -(t.amount() + t.fee());
            netFlow += signed;
            flowCount++;
            System.out.printf(Locale.US, "  %s  %-11s %s%n",
                    TIME_FORMAT.format(Instant.ofEpochMilli(t.timestamp())), t.type(),
                    colored(String.format(Locale.US, "%+,.2f EUR", signed), signed));
        }
        if (flowCount == 0) {
            System.out.println("  no EUR deposits or withdrawals since start");
        } else {
            System.out.printf(Locale.US, "  %-28s %s%n", "net EUR flow since start:",
                    colored(String.format(Locale.US, "%+,.2f EUR", netFlow), netFlow));
        }

        double profit = total - startBalance - netFlow;
        double base = startBalance + Math.max(netFlow, 0);
        String percent = base > 0
                ? String.format(Locale.US, "%+.1f%%", 100 * profit / base)
                : "n/a";
        System.out.printf("  %-28s %s%n", "profit since start:",
                colored(String.format(Locale.US, "%+,.2f EUR   %s", profit, percent), profit));
    }

    private static void warnIgnoredCrypto(List<Transfer> transfers, String kind) {
        long ignored = transfers.stream().filter(t -> !t.symbol().equals("EUR")).count();
        if (ignored > 0) {
            System.err.println(ConsoleColor.orange("Ignored " + ignored + " crypto " + kind
                    + " — net invested covers EUR fiat flows only"));
        }
    }

    private static void warnTruncated(List<Transfer> transfers, String endpoint) {
        if (transfers.size() == BitvavoPrivateClient.HISTORY_LIMIT) {
            System.err.println(ConsoleColor.orange(endpoint + " returned "
                    + BitvavoPrivateClient.HISTORY_LIMIT
                    + " entries — older history may be missing, net invested may be incomplete"));
        }
    }

    private static String colored(String text, double signal) {
        if (signal > 0) {
            return ConsoleColor.green(text);
        }
        if (signal < 0) {
            return ConsoleColor.red(text);
        }
        return text;
    }
}
