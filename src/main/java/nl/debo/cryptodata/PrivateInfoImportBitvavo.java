package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BitvavoClient;
import nl.debo.cryptodata.tools.BitvavoCredentials;
import nl.debo.cryptodata.tools.BitvavoPrivateClient;
import nl.debo.cryptodata.tools.BitvavoPrivateClient.AssetBalance;
import nl.debo.cryptodata.tools.BitvavoPrivateClient.Transfer;
import nl.debo.cryptodata.utils.BalanceCsvStore;
import nl.debo.cryptodata.utils.BalanceCsvStore.SnapshotRow;
import nl.debo.cryptodata.utils.ConsoleColor;
import nl.debo.cryptodata.utils.FileUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Entry point: prints the Bitvavo account balance valued in EUR, with history
 * and stats. Every run appends a snapshot (one row per held asset) to
 * {@code output/balance-bitvavo/snapshots.csv}; the accumulated snapshots
 * drive the change columns and the 24h/7d/30d portfolio stats, so those fill
 * in as the tool is run over time. Net invested and overall P/L are computed
 * from the account's EUR deposit and withdrawal history.
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

        printBalanceTable(rows, history);
        printHistoryStats(now, totalValue(rows), history);
        printProfitAndLoss(totalValue(rows), deposits, withdrawals);

        System.out.println(ConsoleColor.green("Snapshot appended to " + csvPath));
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

    private static void printBalanceTable(List<SnapshotRow> rows, List<SnapshotRow> history) {
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
        System.out.println();
        System.out.printf(Locale.US, "%-8s %16s %14s %14s %14s %7s %10s%n",
                "ASSET", "AVAILABLE", "IN ORDER", "PRICE EUR", "VALUE EUR", "PORT%", "CHANGE");
        for (SnapshotRow row : rows) {
            String price = row.priceEur() == UNPRICED
                    ? "n/a"
                    : String.format(Locale.US, "%,.2f", row.priceEur());
            double share = total > 0 ? 100 * row.valueEur() / total : 0;
            System.out.printf(Locale.US, "%-8s %16.8f %14.8f %14s %14s %6.1f%% %10s%n",
                    row.asset(), row.available(), row.inOrder(), price,
                    String.format(Locale.US, "%,.2f", row.valueEur()), share,
                    changeLabel(hasHistory, previousValues.get(row.asset()), row.valueEur()));
        }
        System.out.printf(Locale.US, "%-8s %16s %14s %14s %14s %6.1f%%%n",
                "TOTAL", "", "", "",
                String.format(Locale.US, "%,.2f", total), rows.isEmpty() ? 0.0 : 100.0);
    }

    /**
     * Percent change against the same asset in the previous snapshot;
     * {@code n/a} on the first run ever, {@code new} for a first-seen asset.
     */
    private static String changeLabel(boolean hasHistory, Double previous, double current) {
        if (!hasHistory) {
            return "n/a";
        }
        if (previous == null) {
            return "new";
        }
        if (previous == 0) {
            return "n/a";
        }
        return coloredPercent(100 * (current - previous) / previous);
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

    private static String coloredPercent(double percent) {
        return colored(String.format(Locale.US, "%+.1f%%", percent), percent);
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
