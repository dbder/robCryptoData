package nl.debo.cryptodata;

import nl.debo.cryptodata.tools.BitvavoClient;
import nl.debo.cryptodata.tools.CryptoAnalysis;
import nl.debo.cryptodata.tools.Indicator;
import nl.debo.cryptodata.tools.KlineHistoryImporter;
import nl.debo.cryptodata.tools.LocalKlineSource;
import nl.debo.cryptodata.utils.FileUtil;

import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Scanner;

/**
 * Entry point: {@link CryptoAnalysisBitvavo} for a date of your choosing.
 * Asks for a date on the console, then runs the analysis as if it were run
 * at the end of that day (UTC): the {@link LocalKlineSource} gets a cutoff
 * so candles that closed later are invisible, and the reports are stamped
 * with the chosen date instead of today.
 *
 * <p>Indicators only look backward, so on the same candle store a backdated
 * run reproduces exactly what a live run at the end of that day would have
 * produced. A weekly or monthly candle still open on the chosen date is
 * ignored, just as its open counterpart is in a live run.</p>
 *
 * <p>Reports land in {@code output/data-bitvavo-fordate<date>.csv/.xlsx} —
 * a separate base name, so a backdated run never appends to the
 * {@code data-bitvavo-local} report that really was written on that day.</p>
 */
public final class CryptoAnalysisBitvavoForDate {

    private static final List<String> INTERVALS = List.of("1d", "1W", "1M");
    /** Resource / file naming the indicators to report on, one per line. */
    private static final String INDICATORS_FILE = "indicators";

    private CryptoAnalysisBitvavoForDate() {
    }

    public static void main(String[] args) throws Exception {
        LocalDate date = askDate();
        Instant cutoff = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();

        Path klinesDir = FileUtil.applicationDir().resolve("output/klines-bitvavo");

        new CryptoAnalysis(
                new LocalKlineSource(klinesDir, new KlineHistoryImporter(new BitvavoClient(), klinesDir), cutoff),
                "symbols-bitvavo",
                INTERVALS,
                Indicator.readSelection(CryptoAnalysisBitvavoForDate.class, INDICATORS_FILE),
                "data-bitvavo-fordate",
                date
        ).run();
    }

    /**
     * Asks for year, month and day separately. Year may be empty (= the
     * current year) or two digits ({@code 26} = 2026); month and day accept
     * the short form without the leading zero ({@code 8} = 08).
     */
    private static LocalDate askDate() {
        var in = new Scanner(System.in);
        int thisYear = LocalDate.now().getYear();
        while (true) {
            int year = askNumber(in, "Year (empty = " + thisYear + "): ", thisYear);
            if (year < 100) {
                year += 2000;
            }
            int month = askNumber(in, "Month (1-12): ", null);
            int day = askNumber(in, "Day (1-31): ", null);
            try {
                return LocalDate.of(year, month, day);
            } catch (DateTimeException e) {
                System.out.println("Not a valid date: " + e.getMessage());
            }
        }
    }

    /** Asks until a number is entered; empty input yields {@code emptyValue} unless that is null. */
    private static int askNumber(Scanner in, String prompt, Integer emptyValue) {
        while (true) {
            System.out.print(prompt);
            String line = in.nextLine().trim();
            if (line.isEmpty() && emptyValue != null) {
                return emptyValue;
            }
            try {
                return Integer.parseInt(line);
            } catch (NumberFormatException e) {
                System.out.println("Not a number: '" + line + "'");
            }
        }
    }
}
