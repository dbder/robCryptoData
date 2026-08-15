package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.FileUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The indicators an analysis run can report on. Which combination is active
 * comes from a small text file (see {@link #readSelection}); everything
 * outside the selection is left out of the row, the console line, the
 * spreadsheet and the combined score.
 *
 * <p>{@link #MACD} stands for the whole MACD family: MACD line, signal
 * line, histogram and the normalized MACD stat.</p>
 */
public enum Indicator {
    RSI("RSI"),
    STOCH_RSI("STOCH-RSI"),
    K("K"),
    D("D"),
    MACD("MACD"),
    MADR("MADR");

    private final String label;

    Indicator(String label) {
        this.label = label;
    }

    /** Name as written in the indicators file, e.g. {@code STOCH-RSI}. */
    public String label() {
        return label;
    }

    /**
     * Parses one line of the indicators file. Case-insensitive; {@code -},
     * {@code _} and spaces are interchangeable, so {@code stoch_rsi} and
     * {@code Stoch RSI} both mean {@link #STOCH_RSI}.
     */
    public static Indicator parse(String text) {
        String key = text.trim().toUpperCase(Locale.ROOT).replace('_', '-').replace(' ', '-');
        return Arrays.stream(values())
                .filter(i -> i.label.equals(key))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown indicator '" + text.trim() + "'; choose from " + labels()));
    }

    /**
     * Turns the lines of an indicators file into a selection: one indicator
     * per line, blank lines and {@code #} comments ignored. Fails when the
     * file selects nothing or names something unknown.
     */
    public static Set<Indicator> parseSelection(List<String> lines) {
        Set<Indicator> selection = EnumSet.noneOf(Indicator.class);
        for (String line : lines) {
            String text = line.strip();
            if (text.isEmpty() || text.startsWith("#")) {
                continue;
            }
            selection.add(parse(text));
        }
        if (selection.isEmpty()) {
            throw new IllegalArgumentException(
                    "The indicators file selects nothing; choose at least one of " + labels());
        }
        return selection;
    }

    /**
     * Reads the selection from {@code fileName}: a file of that name next to
     * the application (or under {@code src/main/resources/...} when run
     * from the IDE) wins over the classpath resource, so a run can be
     * reconfigured without rebuilding.
     *
     * @param resourceOwner class in the package that holds the resource
     */
    public static Set<Indicator> readSelection(Class<?> resourceOwner, String fileName) throws IOException {
        return parseSelection(FileUtil.readLinesWithFallback(
                resourceOwner,
                fileName,
                FileUtil.applicationDir().resolve(fileName),
                Path.of("src/main/resources/nl/debo/cryptodata/" + fileName),
                Path.of(fileName)
        ));
    }

    /**
     * The indicators that carry a value in at least one of {@code rows}, for
     * tools that only have a report to go by (no indicators file).
     */
    public static Set<Indicator> presentIn(List<ResultRow> rows) {
        Set<Indicator> present = EnumSet.noneOf(Indicator.class);
        for (Indicator indicator : values()) {
            if (rows.stream().anyMatch(r -> r.has(indicator))) {
                present.add(indicator);
            }
        }
        return present;
    }

    /** Comma-separated labels of the selection, in declaration order, e.g. {@code RSI, K, MACD}. */
    public static String describe(Set<Indicator> selection) {
        return Arrays.stream(values())
                .filter(selection::contains)
                .map(Indicator::label)
                .collect(Collectors.joining(", "));
    }

    private static String labels() {
        return describe(EnumSet.allOf(Indicator.class));
    }
}
