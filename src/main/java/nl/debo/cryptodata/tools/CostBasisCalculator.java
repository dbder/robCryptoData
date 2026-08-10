package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.TradeCsvStore.TradeRow;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Weighted-average cost basis per asset, computed from a trade ledger. A buy
 * adds its EUR cost (price times amount, plus the EUR fee) to the position; a
 * sell removes a proportional slice of the accumulated cost, so the average
 * buy price of what remains is unchanged and realized gains never leak into
 * the basis of the remaining coins.
 *
 * <p>Only what was traded on the exchange is visible here: coins transferred
 * in or earned have no cost on record, and coins transferred out are not
 * distinguished from coins still held. Callers should compare
 * {@link Basis#amount()} against the actual balance and treat a mismatch as
 * an incomplete basis.</p>
 */
public final class CostBasisCalculator {

    /** Position built from trades: {@code amount} coins costing {@code costEur} in total. */
    public record Basis(double amount, double costEur) {
        public double avgPrice() {
            return amount > 0 ? costEur / amount : 0;
        }
    }

    private CostBasisCalculator() {
    }

    /** Basis per base asset (e.g. {@code "BTC"}), oldest trade first. */
    public static Map<String, Basis> fromTrades(List<TradeRow> trades) {
        var sorted = trades.stream()
                .sorted(Comparator.comparingLong(TradeRow::timestamp))
                .toList();
        var amounts = new HashMap<String, Double>();
        var costs = new HashMap<String, Double>();
        for (TradeRow trade : sorted) {
            String asset = PairSymbols.base(trade.market());
            double amount = amounts.getOrDefault(asset, 0.0);
            double cost = costs.getOrDefault(asset, 0.0);
            if (trade.side().equals("buy")) {
                double eurFee = trade.feeCurrency().equals("EUR") ? trade.fee() : 0;
                amount += trade.amount();
                cost += trade.amount() * trade.price() + eurFee;
            } else {
                // Sells beyond the tracked position (coins transferred in)
                // have no cost on record and are ignored.
                double sold = Math.min(trade.amount(), amount);
                if (amount > 0) {
                    cost -= cost * sold / amount;
                }
                amount -= sold;
                if (amount <= 0) {
                    amount = 0;
                    cost = 0;
                }
            }
            amounts.put(asset, amount);
            costs.put(asset, cost);
        }
        var result = new HashMap<String, Basis>();
        for (var entry : amounts.entrySet()) {
            result.put(entry.getKey(), new Basis(entry.getValue(), costs.get(entry.getKey())));
        }
        return result;
    }
}
