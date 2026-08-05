package nl.debo.cryptodata.utils;

/**
 * XML helpers shared by the ODS and XLSX writers.
 */
public final class XmlUtil {

    private XmlUtil() {
    }

    /**
     * Escapes the five XML special characters. Returns an empty string for null.
     */
    public static String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
