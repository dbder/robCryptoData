package nl.debo.cryptodata.utils;

/**
 * ANSI color helpers for console output: green for the start/end message of a
 * run and positive changes, red for negative changes, orange for warnings
 * (rate limits, retries, skipped symbols).
 */
public final class ConsoleColor {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";

    private static final String RED = "\u001B[31m";

    /** Plain ANSI has no orange; 256-color code 208 is, and modern terminals support it. */
    private static final String ORANGE = "\u001B[38;5;208m";

    private ConsoleColor() {
    }

    public static String green(String text) {
        return GREEN + text + RESET;
    }

    public static String red(String text) {
        return RED + text + RESET;
    }

    public static String orange(String text) {
        return ORANGE + text + RESET;
    }
}
