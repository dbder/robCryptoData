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

            // Second page: a scatter-plot "quadrant" of the weekly (1w) rows,
            // with RSI on the horizontal axis and StochRSI on the vertical axis.
            // The bottom-left quadrant (low RSI + low StochRSI) highlights the
            // most oversold, i.e. good buy, candidates.
            List<CryptoAnalysis.ResultRow> weekly = results.stream()
                    .filter(r -> "1w".equalsIgnoreCase(r.interval()))
                    .toList();
            writeEntry(zipOut, "xl/worksheets/sheet2.xml", sheet2Xml(weekly));
            writeEntry(zipOut, "xl/worksheets/_rels/sheet2.xml.rels", sheet2RelsXml());
            writeEntry(zipOut, "xl/drawings/drawing1.xml", drawingXml());
            writeEntry(zipOut, "xl/drawings/_rels/drawing1.xml.rels", drawingRelsXml());
            writeEntry(zipOut, "xl/charts/chart1.xml", chartXml(weekly));

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
                  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                  <Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/>
                  <Override PartName="/xl/charts/chart1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawingml.chart+xml"/>
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
                    <sheet name="Weekly Quadrant" sheetId="2" r:id="rId3"/>
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
                  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
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
                  <cellXfs count="12">
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
                    <xf numFmtId="165" fontId="0" fillId="0" borderId="1" xfId="0" applyNumberFormat="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" indent="2"/></xf>
                    <xf numFmtId="165" fontId="0" fillId="3" borderId="1" xfId="0" applyNumberFormat="1" applyFill="1" applyBorder="1" applyAlignment="1"><alignment horizontal="right" indent="2"/></xf>
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
            // Colour-coded RSI / StochRSI values get a little extra indent so the
            // highlighted numbers sit slightly inset from the column edge.
            int fourDecIndentStyle = banded ? 11 : 10;

            sb.append(String.format("    <row r=\"%d\">\n", rowNum));
            appendInlineString(sb, cellRef(0, rowNum), textStyle, r.symbol());
            appendInlineString(sb, cellRef(1, rowNum), centerStyle, r.interval());
            appendInlineString(sb, cellRef(2, rowNum), textStyle, r.time());
            appendNumber(sb, cellRef(3, rowNum), twoDecStyle, r.close());
            appendNumber(sb, cellRef(4, rowNum), fourDecIndentStyle, r.rsi());
            appendNumber(sb, cellRef(5, rowNum), fourDecIndentStyle, r.stochRsi());
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

    /**
     * Second worksheet: a compact table (Symbol, RSI, StochRSI) holding only the
     * weekly rows, used as the data source for the scatter chart. The chart is
     * attached through the {@code <drawing>} reference at the end.
     */
    private static String sheet2Xml(List<CryptoAnalysis.ResultRow> weekly) {
        var sb = new StringBuilder();
        sb.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                           xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <sheetViews><sheetView workbookViewId="0"/></sheetViews>
                  <sheetFormatPr defaultRowHeight="15"/>
                  <cols>
                    <col min="1" max="1" width="14" customWidth="1"/>
                    <col min="2" max="3" width="12" customWidth="1"/>
                  </cols>
                  <sheetData>
                """);

        // Header row (re-uses the dark header style from styles.xml).
        sb.append("    <row r=\"1\" ht=\"20\" customHeight=\"1\">\n");
        appendInlineString(sb, "A1", 1, "Symbol");
        appendInlineString(sb, "B1", 1, "RSI");
        appendInlineString(sb, "C1", 1, "StochRSI");
        sb.append("    </row>\n");

        int rowNum = 2;
        for (var r : weekly) {
            sb.append(String.format("    <row r=\"%d\">\n", rowNum));
            appendInlineString(sb, "A" + rowNum, 2, r.symbol());
            appendNumber(sb, "B" + rowNum, 6, r.rsi());
            appendNumber(sb, "C" + rowNum, 8, r.stochRsi());
            sb.append("    </row>\n");
            rowNum++;
        }

        sb.append("  </sheetData>\n");
        sb.append("  <drawing r:id=\"rId1\"/>\n");
        sb.append("</worksheet>\n");
        return sb.toString();
    }

    private static String sheet2RelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/drawing" Target="../drawings/drawing1.xml"/>
                </Relationships>
                """;
    }

    /**
     * Anchors the chart onto the second worksheet. The chart floats over columns
     * E..S so it sits next to the small data table.
     */
    private static String drawingXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing"
                          xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <xdr:twoCellAnchor editAs="oneCell">
                    <xdr:from><xdr:col>4</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>
                    <xdr:to><xdr:col>18</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>34</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>
                    <xdr:graphicFrame macro="">
                      <xdr:nvGraphicFramePr>
                        <xdr:cNvPr id="2" name="Weekly Quadrant Chart"/>
                        <xdr:cNvGraphicFramePr/>
                      </xdr:nvGraphicFramePr>
                      <xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm>
                      <a:graphic>
                        <a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart">
                          <c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rId1"/>
                        </a:graphicData>
                      </a:graphic>
                    </xdr:graphicFrame>
                    <xdr:clientData/>
                  </xdr:twoCellAnchor>
                </xdr:wsDr>
                """;
    }

    private static String drawingRelsXml() {
        return """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/chart" Target="../charts/chart1.xml"/>
                </Relationships>
                """;
    }

    /**
     * The scatter chart itself. RSI is plotted horizontally (0..100), StochRSI
     * vertically (0..1). The two value axes cross at the mid-points (RSI 50 /
     * StochRSI 0.5), which draws the quadrant divider lines; the bottom-left
     * quadrant therefore groups the oversold, i.e. good buy, candidates. Each
     * point is labelled with its symbol via the standard Excel data-label range
     * extension.
     */
    private static String chartXml(List<CryptoAnalysis.ResultRow> weekly) {
        int n = weekly.size();
        int lastRow = n + 1;
        String sheet = "'Weekly Quadrant'";
        var sb = new StringBuilder();

        sb.append("""
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart"
                              xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
                              xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                  <c:chart>
                    <c:title>
                      <c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>Weekly RSI vs StochRSI - bottom-left = buy candidates</a:t></a:r></a:p></c:rich></c:tx>
                      <c:overlay val="0"/>
                    </c:title>
                    <c:autoTitleDeleted val="0"/>
                    <c:plotArea>
                      <c:layout/>
                      <c:scatterChart>
                        <c:scatterStyle val="lineMarker"/>
                        <c:varyColors val="0"/>
                        <c:ser>
                          <c:idx val="0"/>
                          <c:order val="0"/>
                          <c:tx><c:v>Weekly</c:v></c:tx>
                          <c:spPr><a:ln w="19050"><a:noFill/></a:ln></c:spPr>
                          <c:marker>
                            <c:symbol val="circle"/>
                            <c:size val="6"/>
                            <c:spPr><a:solidFill><a:srgbClr val="1F3864"/></a:solidFill><a:ln><a:solidFill><a:srgbClr val="1F3864"/></a:solidFill></a:ln></c:spPr>
                          </c:marker>
                          <c:dLbls>
                            <c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>
                            <c:showLegendKey val="0"/>
                            <c:showVal val="0"/>
                            <c:showCatName val="0"/>
                            <c:showSerName val="0"/>
                            <c:showPercent val="0"/>
                            <c:showBubbleSize val="0"/>
                            <c:extLst>
                              <c:ext uri="{CE6537A1-D6FC-4f65-9D91-7224C49458BB}" xmlns:c15="http://schemas.microsoft.com/office/drawing/2012/chart">
                                <c15:showDataLabelsRange val="1"/>
                              </c:ext>
                            </c:extLst>
                          </c:dLbls>
                """);

        // X values (RSI) with a cache so the chart renders without recalculation.
        sb.append("          <c:xVal><c:numRef><c:f>").append(sheet).append("!$B$2:$B$").append(lastRow).append("</c:f>");
        sb.append("<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"").append(n).append("\"/>");
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "<c:pt idx=\"%d\"><c:v>%s</c:v></c:pt>", i, Double.toString(weekly.get(i).rsi())));
        }
        sb.append("</c:numCache></c:numRef></c:xVal>\n");

        // Y values (StochRSI).
        sb.append("          <c:yVal><c:numRef><c:f>").append(sheet).append("!$C$2:$C$").append(lastRow).append("</c:f>");
        sb.append("<c:numCache><c:formatCode>General</c:formatCode><c:ptCount val=\"").append(n).append("\"/>");
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "<c:pt idx=\"%d\"><c:v>%s</c:v></c:pt>", i, Double.toString(weekly.get(i).stochRsi())));
        }
        sb.append("</c:numCache></c:numRef></c:yVal>\n");

        // Per-point labels = symbol names (Excel 2013 data-label range extension).
        sb.append("          <c:extLst><c:ext uri=\"{02D57815-91ED-43cb-92C2-25804820EDAC}\" xmlns:c15=\"http://schemas.microsoft.com/office/drawing/2012/chart\">");
        sb.append("<c15:datalabelsRange><c15:f>").append(sheet).append("!$A$2:$A$").append(lastRow).append("</c15:f>");
        sb.append("<c15:dlblRangeCache><c:ptCount val=\"").append(n).append("\"/>");
        for (int i = 0; i < n; i++) {
            sb.append(String.format("<c:pt idx=\"%d\"><c:v>%s</c:v></c:pt>", i, escapeXml(weekly.get(i).symbol())));
        }
        sb.append("</c15:dlblRangeCache></c15:datalabelsRange></c:ext></c:extLst>\n");

        sb.append("        </c:ser>\n");
        sb.append("        <c:axId val=\"111111111\"/>\n");
        sb.append("        <c:axId val=\"222222222\"/>\n");
        sb.append("      </c:scatterChart>\n");

        // Horizontal axis: RSI 0..100, crossed by the vertical axis at 50.
        // Vertical axis: StochRSI 0..1, crossed by the horizontal axis at 0.5.
        sb.append("""
                      <c:valAx>
                        <c:axId val="111111111"/>
                        <c:scaling><c:orientation val="minMax"/><c:max val="100"/><c:min val="0"/></c:scaling>
                        <c:delete val="0"/>
                        <c:axPos val="b"/>
                        <c:majorGridlines/>
                        <c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:t>RSI (weekly)</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>
                        <c:numFmt formatCode="General" sourceLinked="1"/>
                        <c:majorTickMark val="out"/>
                        <c:minorTickMark val="none"/>
                        <c:tickLblPos val="low"/>
                        <c:crossAx val="222222222"/>
                        <c:crossesAt val="50"/>
                        <c:majorUnit val="10"/>
                      </c:valAx>
                      <c:valAx>
                        <c:axId val="222222222"/>
                        <c:scaling><c:orientation val="minMax"/><c:max val="1"/><c:min val="0"/></c:scaling>
                        <c:delete val="0"/>
                        <c:axPos val="l"/>
                        <c:majorGridlines/>
                        <c:title><c:tx><c:rich><a:bodyPr rot="-5400000" vert="horz"/><a:lstStyle/><a:p><a:r><a:t>StochRSI (weekly)</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title>
                        <c:numFmt formatCode="General" sourceLinked="1"/>
                        <c:majorTickMark val="out"/>
                        <c:minorTickMark val="none"/>
                        <c:tickLblPos val="low"/>
                        <c:crossAx val="111111111"/>
                        <c:crossesAt val="0.5"/>
                        <c:majorUnit val="0.1"/>
                      </c:valAx>
                    </c:plotArea>
                    <c:plotVisOnly val="1"/>
                    <c:dispBlanksAs val="gap"/>
                  </c:chart>
                </c:chartSpace>
                """);

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
