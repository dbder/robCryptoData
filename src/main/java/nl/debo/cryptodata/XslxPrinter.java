package nl.debo.cryptodata;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes a nicely styled, sortable Microsoft Excel (.xlsx) spreadsheet from a
 * list of {@link CryptoAnalysis.ResultRow} values.
 *
 * <p>The file is produced as a raw OOXML (SpreadsheetML) package, so no external
 * libraries are required. The layout focuses on readability:
 * <ul>
 *     <li>a bold, coloured header row that stays frozen while scrolling;</li>
 *     <li>an auto-filter so every column is instantly sortable/filterable;</li>
 *     <li>tuned column widths and number formats;</li>
 *     <li>banded (zebra) rows and colour-coded RSI / StochRSI cells.</li>
 * </ul>
 */
public final class XslxPrinter {

    private XslxPrinter() {
    }

    private static final String[] HEADERS = {
            "Symbol", "Interval", "Time", "Close", "RSI", "StochRSI", "K", "D"
    };

    // Column widths (in Excel "characters" units).
    private static final double[] COLUMN_WIDTHS = {
            14, 11, 26, 15, 10, 12, 10, 10
    };

    /**
     * Generates the spreadsheet.
     *
     * @param xlsxPath destination file
     * @param dateStr  report date, used in document metadata
     * @param results  rows to render
     */
    public static void write(Path xlsxPath, String dateStr, List<CryptoAnalysis.ResultRow> results) {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(xlsxPath))) {
            writeEntry(zipOut, "[Content_Types].xml", contentTypesXml());
            writeEntry(zipOut, "_rels/.rels", rootRelsXml());
            writeEntry(zipOut, "docProps/core.xml", corePropsXml(dateStr));
            writeEntry(zipOut, "docProps/app.xml", appPropsXml());
            writeEntry(zipOut, "xl/workbook.xml", workbookXml());
            writeEntry(zipOut, "xl/_rels/workbook.xml.rels", workbookRelsXml());
            writeEntry(zipOut, "xl/styles.xml", stylesXml());
            writeEntry(zipOut, "xl/worksheets/sheet1.xml", sheetXml(results));

            System.out.println("Successfully generated XLSX report: " + xlsxPath);
        } catch (Exception e) {
            System.err.println("Error generating XLSX file: " + e.getMessage());
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

    private static String sheetXml(List<CryptoAnalysis.ResultRow> results) {
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
        for (int c = 0; c < COLUMN_WIDTHS.length; c++) {
            sb.append(String.format(
                    Locale.US,
                    "    <col min=\"%d\" max=\"%d\" width=\"%.2f\" customWidth=\"1\"/>\n",
                    c + 1, c + 1, COLUMN_WIDTHS[c]));
        }
        sb.append("  </cols>\n");

        sb.append("  <sheetData>\n");

        // Header row.
        sb.append("    <row r=\"1\" ht=\"20\" customHeight=\"1\">\n");
        for (int c = 0; c < HEADERS.length; c++) {
            String ref = cellRef(c, 1);
            sb.append(String.format(
                    "      <c r=\"%s\" s=\"1\" t=\"inlineStr\"><is><t>%s</t></is></c>\n",
                    ref, escapeXml(HEADERS[c])));
        }
        sb.append("    </row>\n");

        // Data rows.
        int rowNum = 2;
        for (var r : results) {
            boolean banded = (rowNum % 2) == 0;
            int textStyle = banded ? 3 : 2;
            int centerStyle = banded ? 5 : 4;
            int twoDecStyle = banded ? 7 : 6;
            int fourDecStyle = banded ? 9 : 8;

            sb.append(String.format("    <row r=\"%d\">\n", rowNum));
            appendInlineString(sb, cellRef(0, rowNum), textStyle, r.symbol());
            appendInlineString(sb, cellRef(1, rowNum), centerStyle, r.interval());
            appendInlineString(sb, cellRef(2, rowNum), textStyle, r.time());
            appendNumber(sb, cellRef(3, rowNum), twoDecStyle, r.close());
            appendNumber(sb, cellRef(4, rowNum), fourDecStyle, r.rsi());
            appendNumber(sb, cellRef(5, rowNum), fourDecStyle, r.stochRsi());
            appendNumber(sb, cellRef(6, rowNum), fourDecStyle, r.k());
            appendNumber(sb, cellRef(7, rowNum), fourDecStyle, r.d());
            sb.append("    </row>\n");
            rowNum++;
        }

        sb.append("  </sheetData>\n");

        // Auto-filter makes every column sortable / filterable.
        int lastCol = HEADERS.length;
        sb.append(String.format(
                "  <autoFilter ref=\"A1:%s%d\"/>\n",
                columnLetters(lastCol - 1), Math.max(lastRow, 1)));

        // Colour-code the RSI column: overbought (>=70) red, oversold (<=30) green.
        if (!results.isEmpty()) {
            String rsiRange = "E2:E" + lastRow;
            sb.append(String.format("""
                      <conditionalFormatting sqref="%s">
                        <cfRule type="cellIs" dxfId="0" priority="1" operator="greaterThanOrEqual"><formula>70</formula></cfRule>
                        <cfRule type="cellIs" dxfId="1" priority="2" operator="lessThanOrEqual"><formula>30</formula></cfRule>
                      </conditionalFormatting>
                    """, rsiRange));

            // StochRSI is scaled 0..1: overbought (>=0.8) red, oversold (<=0.2) green.
            String stochRange = "F2:F" + lastRow;
            sb.append(String.format("""
                      <conditionalFormatting sqref="%s">
                        <cfRule type="cellIs" dxfId="0" priority="3" operator="greaterThanOrEqual"><formula>0.8</formula></cfRule>
                        <cfRule type="cellIs" dxfId="1" priority="4" operator="lessThanOrEqual"><formula>0.2</formula></cfRule>
                      </conditionalFormatting>
                    """, stochRange));
        }

        sb.append("</worksheet>\n");
        return sb.toString();
    }

    private static void appendInlineString(StringBuilder sb, String ref, int style, String value) {
        sb.append(String.format(
                "      <c r=\"%s\" s=\"%d\" t=\"inlineStr\"><is><t>%s</t></is></c>\n",
                ref, style, escapeXml(value)));
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

    private static String escapeXml(String str) {
        if (str == null) return "";
        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
