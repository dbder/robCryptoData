package nl.debo.cryptodata;

import nl.debo.cryptodata.utils.XmlUtil;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Writes an OpenDocument (.ods) spreadsheet from a list of {@link ResultRow}
 * values. The file is produced as a raw ODF package, so no external libraries
 * are required.
 */
public final class OdsPrinter {

    private OdsPrinter() {
    }

    /**
     * Generates the spreadsheet.
     *
     * @param odsPath destination file
     * @param dateStr report date, used in document metadata
     * @param results rows to render
     */
    public static void write(Path odsPath, String dateStr, List<ResultRow> results) {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(odsPath))) {
            // 1. mimetype (must be first, stored, uncompressed)
            var mimeBytes = "application/vnd.oasis.opendocument.spreadsheet".getBytes(StandardCharsets.US_ASCII);
            var mimeEntry = new ZipEntry("mimetype");
            mimeEntry.setMethod(ZipEntry.STORED);
            mimeEntry.setSize(mimeBytes.length);
            mimeEntry.setCompressedSize(mimeBytes.length);
            var crc = new CRC32();
            crc.update(mimeBytes);
            mimeEntry.setCrc(crc.getValue());
            zipOut.putNextEntry(mimeEntry);
            zipOut.write(mimeBytes);
            zipOut.closeEntry();

            writeEntry(zipOut, "META-INF/manifest.xml", manifestXml());
            writeEntry(zipOut, "meta.xml", metaXml(dateStr));
            writeEntry(zipOut, "styles.xml", stylesXml());
            writeEntry(zipOut, "content.xml", contentXml(results));

            System.out.println("Successfully generated ODS/ODF report: " + odsPath);
        } catch (Exception e) {
            System.err.println("Error generating ODS/ODF file: " + e.getMessage());
        }
    }

    private static void writeEntry(ZipOutputStream zipOut, String name, String content) throws Exception {
        zipOut.putNextEntry(new ZipEntry(name));
        zipOut.write(content.getBytes(StandardCharsets.UTF_8));
        zipOut.closeEntry();
    }

    private static String manifestXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                  <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>
                  <manifest:file-entry manifest:full-path="META-INF/manifest.xml" manifest:media-type="text/xml"/>
                  <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
                  <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                  <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
                </manifest:manifest>
                """;
    }

    private static String metaXml(String dateStr) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/" office:version="1.2">
                  <office:meta>
                    <dc:creator>CryptoAnalysis</dc:creator>
                    <dc:date>%sT00:00:00Z</dc:date>
                  </office:meta>
                </office:document-meta>
                """.formatted(dateStr);
    }

    private static String stylesXml() {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" office:version="1.2">
                  <office:styles/>
                  <office:automatic-styles/>
                  <office:master-styles/>
                </office:document-styles>
                """;
    }

    private static String contentXml(List<ResultRow> results) {
        var contentBuilder = new StringBuilder();
        contentBuilder.append("""
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                         xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
                                         xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                         xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                                         xmlns:calcext="urn:org:documentfoundation:names:experimental:calc:xmlns:calcext:1.0"
                                         office:version="1.2">
                  <office:body>
                    <office:spreadsheet>
                      <table:table table:name="CryptoResults">
                        <table:table-column table:number-columns-repeated="11"/>
                        <table:table-row>
                          <table:table-cell><text:p>Symbol</text:p></table:table-cell>
                          <table:table-cell><text:p>Interval</text:p></table:table-cell>
                          <table:table-cell><text:p>Time</text:p></table:table-cell>
                          <table:table-cell><text:p>Close</text:p></table:table-cell>
                          <table:table-cell><text:p>RSI</text:p></table:table-cell>
                          <table:table-cell><text:p>StochRsi</text:p></table:table-cell>
                          <table:table-cell><text:p>K</text:p></table:table-cell>
                          <table:table-cell><text:p>D</text:p></table:table-cell>
                          <table:table-cell><text:p>MACD</text:p></table:table-cell>
                          <table:table-cell><text:p>Signal</text:p></table:table-cell>
                          <table:table-cell><text:p>Histogram</text:p></table:table-cell>
                        </table:table-row>
                """);

        for (var r : results) {
            contentBuilder.append(String.format(Locale.US, """
                        <table:table-row>
                          <table:table-cell office:value-type="string"><text:p>%s</text:p></table:table-cell>
                          <table:table-cell office:value-type="string"><text:p>%s</text:p></table:table-cell>
                          <table:table-cell office:value-type="date" office:date-value="%s"><text:p>%s</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="%f"><text:p>%.2f</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="%f"><text:p>%.4f</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="%f"><text:p>%.4f</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="%f"><text:p>%.4f</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="%f"><text:p>%.4f</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="%f"><text:p>%.4f</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="%f"><text:p>%.4f</text:p></table:table-cell>
                          <table:table-cell office:value-type="float" office:value="%f"><text:p>%.4f</text:p></table:table-cell>
                        </table:table-row>
                    """,
                    XmlUtil.escapeXml(r.symbol()),
                    XmlUtil.escapeXml(r.interval()),
                    XmlUtil.escapeXml(r.time()), XmlUtil.escapeXml(r.time()),
                    r.close(), r.close(),
                    r.rsi(), r.rsi(),
                    r.stochRsi(), r.stochRsi(),
                    r.k(), r.k(),
                    r.d(), r.d(),
                    r.macd(), r.macd(),
                    r.macdSignal(), r.macdSignal(),
                    r.macdHistogram(), r.macdHistogram()
            ));
        }

        contentBuilder.append("""
                      </table:table>
                    </office:spreadsheet>
                  </office:body>
                </office:document-content>
                """);

        return contentBuilder.toString();
    }
}
