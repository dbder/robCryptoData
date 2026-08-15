package nl.debo.cryptodata.tools;

import nl.debo.cryptodata.utils.ConsoleColor;
import nl.debo.cryptodata.utils.XmlUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a nicely styled, sortable Microsoft Excel (.xlsx) spreadsheet from a
 * list of {@link ResultRow} values.
 *
 * <p>The file is produced as a raw OOXML (SpreadsheetML) package, so no external
 * libraries are required. The layout focuses on readability:
 * <ul>
 *     <li>a bold, coloured header row that stays frozen while scrolling;</li>
 *     <li>an auto-filter so every column is instantly sortable/filterable;</li>
 *     <li>tuned column widths and number formats;</li>
 *     <li>banded (zebra) rows and colour-coded RSI / StochRSI cells.</li>
 * </ul>
 *
 * <p>The columns are described once in {@link #COLUMNS}; the indicator
 * columns carry the {@link Indicator} they belong to and are only rendered
 * when that indicator is part of the run, so a report on RSI and MACD alone
 * has no StochRSI / K / D columns at all.</p>
 */
public final class XslxPrinter {

    private XslxPrinter() {
    }

    /** How a column's cells are styled: text left, text centered, or a number with 2 or 4 decimals. */
    private enum Kind { TEXT, CENTER, TWO_DEC, FOUR_DEC }

    /**
     * Conditional formatting of a numeric column: cells matching
     * {@code redOp redValue} turn red, cells matching {@code greenOp
     * greenValue} turn green (SpreadsheetML {@code cellIs} operators).
     */
    private record Highlight(String redOp, double redValue, String greenOp, double greenValue) {

        /** Sell side (≥ {@code sellFrom}) red, buy side (≤ {@code buyTo}) green. */
        static Highlight band(double sellFrom, double buyTo) {
            return new Highlight("greaterThanOrEqual", sellFrom, "lessThanOrEqual", buyTo);
        }

        /** Above zero green (bullish), below zero red (bearish). */
        static Highlight aroundZero() {
            return new Highlight("lessThan", 0, "greaterThan", 0);
        }
    }

    /**
     * One spreadsheet column: header, width (Excel "characters"), cell
     * style, the {@link Indicator} it depends on ({@code null} = always
     * shown), the value per row (a {@link String} or a {@link Double}) and
     * optional conditional formatting.
     */
    private record Column(String header, double width, Kind kind, Indicator gate,
                          Function<ResultRow, Object> value, Highlight highlight) {

        static Column text(String header, double width, Function<ResultRow, String> value) {
            return new Column(header, width, Kind.TEXT, null, value::apply, null);
        }

        static Column center(String header, double width, Indicator gate, Function<ResultRow, String> value) {
            return new Column(header, width, Kind.CENTER, gate, value::apply, null);
        }

        static Column number(String header, double width, Kind kind, Indicator gate,
                             Function<ResultRow, Double> value, Highlight highlight) {
            return new Column(header, width, kind, gate, value::apply, highlight);
        }

        boolean shownFor(Set<Indicator> indicators) {
            return gate == null || indicators.contains(gate);
        }
    }

    // RSI is 0..100: overbought (>=70) red, oversold (<=30) green. The 0..1
    // stats (StochRSI, MADR, MACD stat) flag >=0.8 / <=0.2. The score
    // averages several stats, so extremes are diluted: flag confluence at
    // >=0.7 (sell) and <=0.3 (buy) instead.
    private static final List<Column> COLUMNS = List.of(
            Column.text("Symbol", 14, ResultRow::symbol),
            Column.center("EUR/USD", 10, null, ResultRow::quoteCurrency),
            Column.center("Interval", 11, null, ResultRow::interval),
            Column.center("Begin", 14, null, ResultRow::begin),
            Column.text("Time", 26, ResultRow::time),
            Column.number("Close", 15, Kind.TWO_DEC, null, ResultRow::close, null),
            Column.number("RSI", 10, Kind.FOUR_DEC, Indicator.RSI, ResultRow::rsi, Highlight.band(70, 30)),
            Column.center("RSI Range", 11, Indicator.RSI, ResultRow::rsiRange),
            Column.number("StochRSI", 12, Kind.FOUR_DEC, Indicator.STOCH_RSI, ResultRow::stochRsi, Highlight.band(0.8, 0.2)),
            Column.center("StochRSI Range", 15, Indicator.STOCH_RSI, ResultRow::stochRsiRange),
            Column.number("K", 10, Kind.FOUR_DEC, Indicator.K, ResultRow::k, null),
            Column.center("K Range", 10, Indicator.K, ResultRow::kRange),
            Column.number("D", 10, Kind.FOUR_DEC, Indicator.D, ResultRow::d, null),
            Column.center("D Range", 10, Indicator.D, ResultRow::dRange),
            Column.number("MACD", 12, Kind.FOUR_DEC, Indicator.MACD, ResultRow::macd, null),
            Column.center("MACD Range", 13, Indicator.MACD, ResultRow::macdRange),
            Column.number("Signal", 12, Kind.FOUR_DEC, Indicator.MACD, ResultRow::macdSignal, null),
            Column.center("Signal Range", 13, Indicator.MACD, ResultRow::macdSignalRange),
            Column.number("Histogram", 12, Kind.FOUR_DEC, Indicator.MACD, ResultRow::macdHistogram, Highlight.aroundZero()),
            Column.center("Hist Range", 12, Indicator.MACD, ResultRow::macdHistogramRange),
            Column.number("MADR", 10, Kind.FOUR_DEC, Indicator.MADR, ResultRow::madr, Highlight.band(0.8, 0.2)),
            Column.center("MADR Range", 12, Indicator.MADR, ResultRow::madrRange),
            Column.number("MACD Stat", 11, Kind.FOUR_DEC, Indicator.MACD, ResultRow::macdStat, Highlight.band(0.8, 0.2)),
            Column.center("MACD Stat Range", 15, Indicator.MACD, ResultRow::macdStatRange),
            Column.number("Score", 10, Kind.FOUR_DEC, null, ResultRow::score, Highlight.band(0.7, 0.3)),
            Column.center("Score Range", 12, null, ResultRow::scoreRange)
    );

    /**
     * Generates the spreadsheet.
     *
     * @param xlsxPath   destination file
     * @param dateStr    report date, used in document metadata
     * @param results    rows to render
     * @param indicators the indicators of the run; columns of other
     *                   indicators are left out
     */
    public static void write(Path xlsxPath, String dateStr, List<ResultRow> results, Set<Indicator> indicators) {
        List<Column> columns = COLUMNS.stream()
                .filter(c -> c.shownFor(indicators))
                .toList();
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(xlsxPath))) {
            writeEntry(zipOut, "[Content_Types].xml", contentTypesXml());
            writeEntry(zipOut, "_rels/.rels", rootRelsXml());
            writeEntry(zipOut, "docProps/core.xml", corePropsXml(dateStr));
            writeEntry(zipOut, "docProps/app.xml", appPropsXml());
            writeEntry(zipOut, "xl/workbook.xml", workbookXml());
            writeEntry(zipOut, "xl/_rels/workbook.xml.rels", workbookRelsXml());
            writeEntry(zipOut, "xl/styles.xml", stylesXml());
            writeEntry(zipOut, "xl/worksheets/sheet1.xml", sheetXml(columns, results));

            System.out.println(ConsoleColor.green("Successfully generated XLSX report: " + xlsxPath));
        } catch (Exception e) {
            System.err.println(ConsoleColor.orange("Error generating XLSX file: " + e.getMessage()));
        }
    }

    private static void writeEntry(ZipOutputStream zipOut, String name, String content) throws Exception {
        zipOut.putNextEntry(new ZipEntry(name));
        zipOut.write(content.getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();
    }

    private static String contentTypesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                  <Default Extension="xml" ContentType="application/xml"/>
                  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
                  <Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>
                  <Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/>
                </Types>
                """;
    }

    private static String rootRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/>
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties" Target="docProps/app.xml"/>
                </Relationships>
                """;
    }

    private static String corePropsXml(String dateStr) {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties"
                                   xmlns:dc="http://purl.org/dc/elements/1.1/"
                                   xmlns:dcterms="http://purl.org/dc/terms/"
                                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
                  <dc:creator>CryptoAnalysis</dc:creator>
                  <cp:lastModifiedBy>CryptoAnalysis</cp:lastModifiedBy>
                  <dc:title>Crypto Indicators Report</dc:title>
                  <dcterms:created xsi:type="dcterms:W3CDTF">%sT00:00:00Z</dcterms:created>
                  <dcterms:modified xsi:type="dcterms:W3CDTF">%sT00:00:00Z</dcterms:modified>
                </cp:coreProperties>
                """.formatted(dateStr, dateStr);
    }

    private static String appPropsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties">
                  <Application>CryptoAnalysis</Application>
                </Properties>
                """;
    }

    private static String workbookXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheets>
                    <sheet name="Crypto Results" sheetId="1" r:id="rId1"/>
                  </sheets>
                </workbook>
                """;
    }

    private static String workbookRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
                </Relationships>
                """;
    }

    /**
     * Defines the visual language of the sheet: fonts, fills, borders, number
     * formats, the cell style table (cellXfs) and the differential styles used
     * by conditional formatting.
     */
    private static String stylesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <numFmts count="2">
                    <numFmt numFmtId="164" formatCode="0.00"/>
                    <numFmt numFmtId="165" formatCode="0.0000"/>
                  </numFmts>
                  <fonts count="3">
                    <font><sz val="11"/><name val="Calibri"/></font>
                    <font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="Calibri"/></font>
                    <font><sz val="11"/><color rgb="FF1F3864"/><name val="Calibri"/></font>
                  </fonts>
                  <fills count="4">
                    <fill><patternFill patternType="none"/></fill>
                    <fill><patternFill patternType="gray125"/></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FF1F3864"/><bgColor indexed="64"/></patternFill></fill>
                    <fill><patternFill patternType="solid"><fgColor rgb="FFEAF1FB"/><bgColor indexed="64"/></patternFill></fill>
                  </fills>
                  <borders count="2">
                    <border><left/><right/><top/><bottom/><diagonal/></border>
                    <border>
                      <left style="thin"><color rgb="FFBFBFBF"/></left>
                      <right style="thin"><color rgb="FFBFBFBF"/></right>
                      <top style="thin"><color rgb="FFBFBFBF"/></top>
                      <bottom style="thin"><color rgb="FFBFBFBF"/></bottom>
                      <diagonal/>
                    </border>
                  </borders>
                  <cellStyleXfs count="1">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
                  </cellStyleXfs>
                  <cellXfs count="10">
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
                    <xf numFmtId="0" fontId="1" fillId="2" borderId="1" xfId="0" applyFont="1" applyFill="1" applyBorder="1" applyAlignment="1">
                      <alignment horizontal="center" vertical="center"/>
                    </xf>
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="left"/></xf>
                    <xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="left"/></xf>
                    <xf numFmtId="0" fontId="0" fillId="0" borderId="1" xfId="0" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>
                    <xf numFmtId="0" fontId="0" fillId="3" borderId="1" xfId="0" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="center"/></xf>
                    <xf numFmtId="164" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
                    <xf numFmtId="164" fontId="0" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1"/>
                    <xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1"/>
                    <xf numFmtId="165" fontId="0" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1"/>
                  </cellXfs>
                  <cellStyles count="1">
                    <cellStyle name="Normal" xfId="0" builtinId="0"/>
                  </cellStyles>
                  <dxfs count="2">
                    <dxf>
                      <font><color rgb="FF9C0006"/></font>
                      <fill><patternFill><bgColor rgb="FFFFC7CE"/></patternFill></fill>
                    </dxf>
                    <dxf>
                      <font><color rgb="FF006100"/></font>
                      <fill><patternFill><bgColor rgb="FFC6EFCE"/></patternFill></fill>
                    </dxf>
                  </dxfs>
                </styleSheet>
                """;
    }

    private static String sheetXml(List<Column> columns, List<ResultRow> results) {
        int lastRow = results.size() + 1;
        var sb = new StringBuilder();
        sb.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                """);

        // Freeze the header row.
        sb.append("""
                  <sheetViews>
                    <sheetView tabSelected="1" workbookViewId="0">
                      <pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/>
                      <selection pane="bottomLeft" activeCell="A2" sqref="A2"/>
                    </sheetView>
                  </sheetViews>
                """);

        sb.append("  <sheetFormatPr defaultRowHeight=\"15\"/>\n");

        // Column widths.
        sb.append("  <cols>\n");
        for (int c = 0; c < columns.size(); c++) {
            sb.append(String.format(
                    Locale.US,
                    "    <col min=\"%d\" max=\"%d\" width=\"%.2f\" customWidth=\"1\"/>\n",
                    c + 1, c + 1, columns.get(c).width()));
        }
        sb.append("  </cols>\n");

        sb.append("  <sheetData>\n");

        // Header row.
        sb.append("    <row r=\"1\" ht=\"20\" customHeight=\"1\">\n");
        for (int c = 0; c < columns.size(); c++) {
            String ref = cellRef(c, 1);
            sb.append(String.format(
                    "      <c r=\"%s\" s=\"1\" t=\"inlineStr\"><is><t>%s</t></is></c>\n",
                    ref, XmlUtil.escapeXml(columns.get(c).header())));
        }
        sb.append("    </row>\n");

        // Data rows.
        int rowNum = 2;
        for (var r : results) {
            boolean banded = (rowNum % 2) == 0;
            sb.append(String.format("    <row r=\"%d\">\n", rowNum));
            for (int c = 0; c < columns.size(); c++) {
                Column column = columns.get(c);
                String ref = cellRef(c, rowNum);
                Object value = column.value().apply(r);
                switch (column.kind()) {
                    case TEXT -> appendInlineString(sb, ref, banded ? 3 : 2, (String) value);
                    case CENTER -> appendInlineString(sb, ref, banded ? 5 : 4, (String) value);
                    case TWO_DEC -> appendNumber(sb, ref, banded ? 7 : 6, (Double) value);
                    case FOUR_DEC -> appendNumber(sb, ref, banded ? 9 : 8, (Double) value);
                }
            }
            sb.append("    </row>\n");
            rowNum++;
        }

        sb.append("  </sheetData>\n");

        // Auto-filter makes every column sortable / filterable.
        sb.append(String.format(
                "  <autoFilter ref=\"A1:%s%d\"/>\n",
                columnLetters(columns.size() - 1), Math.max(lastRow, 1)));

        // Colour-code the highlighted columns (dxfId 0 = red, 1 = green).
        // Rule priorities must be unique across the sheet.
        if (!results.isEmpty()) {
            int priority = 1;
            for (int c = 0; c < columns.size(); c++) {
                Highlight highlight = columns.get(c).highlight();
                if (highlight == null) {
                    continue;
                }
                String range = columnLetters(c) + "2:" + columnLetters(c) + lastRow;
                sb.append(String.format(Locale.US, """
                          <conditionalFormatting sqref="%s">
                            <cfRule type="cellIs" dxfId="0" priority="%d" operator="%s"><formula>%s</formula></cfRule>
                            <cfRule type="cellIs" dxfId="1" priority="%d" operator="%s"><formula>%s</formula></cfRule>
                          </conditionalFormatting>
                        """, range,
                        priority++, highlight.redOp(), formula(highlight.redValue()),
                        priority++, highlight.greenOp(), formula(highlight.greenValue())));
            }
        }

        sb.append("</worksheet>\n");
        return sb.toString();
    }

    /** Threshold as Excel writes it: {@code 70}, {@code 0.8}, {@code 0}. */
    private static String formula(double value) {
        return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
    }

    private static void appendInlineString(StringBuilder sb, String ref, int style, String value) {
        sb.append(String.format(
                "      <c r=\"%s\" s=\"%d\" t=\"inlineStr\"><is><t>%s</t></is></c>\n",
                ref, style, XmlUtil.escapeXml(value)));
    }

    private static void appendNumber(StringBuilder sb, String ref, int style, double value) {
        sb.append(String.format(
                Locale.US,
                "      <c r=\"%s\" s=\"%d\"><v>%s</v></c>\n",
                ref, style, Double.toString(value)));
    }

    private static String cellRef(int colIndex, int row) {
        return columnLetters(colIndex) + row;
    }

    private static String columnLetters(int colIndex) {
        var sb = new StringBuilder();
        int i = colIndex;
        while (i >= 0) {
            sb.insert(0, (char) ('A' + (i % 26)));
            i = (i / 26) - 1;
        }
        return sb.toString();
    }

}
