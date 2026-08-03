package nl.debo.cryptodata;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public final class CryptoAnalysis {

    private static final int RSI_PERIOD = 14;
    private static final int STOCH_RSI_PERIOD = 14;
    private static final int K_PERIOD = 3;
    private static final int D_PERIOD = 3;

    public record ResultRow(String symbol, String interval, String time, double close, double rsi, double stochRsi, double k, double d) {}

    public static void main(String[] args) throws Exception {
        var client = new BinanceClient();

        List<String> symbols;
        Path path = Path.of("src/main/java/nl/debo/cryptodata/symbols");
        if (!Files.exists(path)) {
            path = Path.of("symbols");
        }
        if (Files.exists(path)) {
            symbols = Files.readAllLines(path);
        } else {
            var resource = CryptoAnalysis.class.getResourceAsStream("symbols");
            if (resource != null) {
                try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resource, StandardCharsets.UTF_8))) {
                    symbols = reader.lines().toList();
                }
            } else {
                throw new IOException("Symbols file not found");
            }
        }

        List<String> intervals = List.of(
                "1h",
                "1d",
                "1w",
                "1M"
        );

        var dateStr = java.time.LocalDate.now().toString();
        var csvPath = Path.of("data" + dateStr + ".csv");
        var odsPath = Path.of("data" + dateStr + ".ods");
        var header = "symbol,interval,time,close,rsi,stochRsi,k,d";
        if (!Files.exists(csvPath)) {
            Files.writeString(csvPath, header + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE);
        }

        List<ResultRow> results = new ArrayList<>();

        for (String rawSymbol : symbols) {
            var symbol = rawSymbol.trim();
            if (symbol.isEmpty() || symbol.startsWith("#")) {
                continue;
            }

            for (String interval : intervals) {
                try {
                    var klines = client.getKlines(
                            symbol,
                            interval,
                            200
                    );

                    if (klines.size() <= 1) {
                        continue;
                    }

                    // Remove the currently open candle.
                    var closedKlines = klines.subList(
                            0,
                            klines.size() - 1
                    );

                    var closes = closedKlines.stream()
                            .map(Kline::close)
                            .toList();

                    var rsi = Indicators.rsi(
                            closes,
                            RSI_PERIOD
                    );

                    var stochRsi = Indicators.stochasticRsi(
                            rsi,
                            STOCH_RSI_PERIOD
                    );

                    var k = Indicators.sma(
                            stochRsi,
                            K_PERIOD
                    );

                    var d = Indicators.sma(
                            k,
                            D_PERIOD
                    );

                    if (rsi.isEmpty() || stochRsi.isEmpty() || k.isEmpty() || d.isEmpty()) {
                        continue;
                    }

                    for (int i = closedKlines.size() - 1; i >= 0; i--) {

                        if (i >= rsi.size() || i >= stochRsi.size() || i >= k.size() || i >= d.size()) {
                            continue;
                        }

                        if (Double.isNaN(rsi.get(i))
                                || Double.isNaN(stochRsi.get(i))
                                || Double.isNaN(k.get(i))
                                || Double.isNaN(d.get(i))) {

                            continue;
                        }

                        var timeInst = java.time.Instant.ofEpochMilli(
                                closedKlines.get(i).closeTime()
                        );
                        System.out.printf(
                                "%s | %s | %s | Close: %.2f | RSI: %.2f | StochRSI: %.4f | K: %.4f | D: %.4f%n",
                                symbol,
                                interval,
                                timeInst,
                                closedKlines.get(i).close(),
                                rsi.get(i),
                                stochRsi.get(i),
                                k.get(i),
                                d.get(i)
                        );
                        var csvLine = String.format(
                                "%s,%s,%s,%.2f,%.4f,%.4f,%.4f,%.4f",
                                symbol,
                                interval,
                                timeInst,
                                closedKlines.get(i).close(),
                                rsi.get(i),
                                stochRsi.get(i),
                                k.get(i),
                                d.get(i)
                        );
                        Files.writeString(csvPath, csvLine + System.lineSeparator(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                        results.add(new ResultRow(symbol, interval, timeInst.toString(), closedKlines.get(i).close(), rsi.get(i), stochRsi.get(i), k.get(i), d.get(i)));
                        break;
                    }

                    Thread.sleep(50);
                } catch (Exception e) {
                    System.err.println("Error processing symbol " + symbol + " (" + interval + "): " + e.getMessage());
                }
            }
        }

        // Generate ODT, ODS, and ODF files
//        generateOdt(odtPath, dateStr, results);
        generateOds(odsPath, dateStr, results);
//        generateOds(odfPath, dateStr, results);
    }

    private static void generateOdt(Path odtPath, String dateStr, List<ResultRow> results) {
        try (var zipOut = new ZipOutputStream(Files.newOutputStream(odtPath))) {
            // 1. mimetype (must be first, stored, uncompressed)
            var mimeBytes = "application/vnd.oasis.opendocument.text".getBytes(StandardCharsets.US_ASCII);
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

            // 2. META-INF/manifest.xml
            zipOut.putNextEntry(new ZipEntry("META-INF/manifest.xml"));
            var manifestXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                      <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.text"/>
                      <manifest:file-entry manifest:full-path="META-INF/manifest.xml" manifest:media-type="text/xml"/>
                      <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
                      <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                      <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
                    </manifest:manifest>
                    """;
            zipOut.write(manifestXml.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // 3. meta.xml
            zipOut.putNextEntry(new ZipEntry("meta.xml"));
            var metaXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/" office:version="1.2">
                      <office:meta>
                        <dc:creator>CryptoAnalysis</dc:creator>
                        <dc:date>%sT00:00:00Z</dc:date>
                      </office:meta>
                    </office:document-meta>
                    """.formatted(dateStr);
            zipOut.write(metaXml.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // 4. styles.xml
            zipOut.putNextEntry(new ZipEntry("styles.xml"));
            var stylesXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" office:version="1.2">
                      <office:styles/>
                      <office:automatic-styles/>
                      <office:master-styles/>
                    </office:document-styles>
                    """;
            zipOut.write(stylesXml.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // 5. content.xml
            zipOut.putNextEntry(new ZipEntry("content.xml"));
            var contentBuilder = new StringBuilder();
            contentBuilder.append("""
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
                                             xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
                                             xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
                                             xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
                                             office:version="1.2">
                      <office:body>
                        <office:text>
                          <text:h text:outline-level="1">Crypto Analysis Results - %s</text:h>
                          <table:table table:name="CryptoResults">
                            <table:table-column table:number-columns-repeated="8"/>
                            <table:table-row>
                              <table:table-cell><text:p>Symbol</text:p></table:table-cell>
                              <table:table-cell><text:p>Interval</text:p></table:table-cell>
                              <table:table-cell><text:p>Time</text:p></table:table-cell>
                              <table:table-cell><text:p>Close</text:p></table:table-cell>
                              <table:table-cell><text:p>RSI</text:p></table:table-cell>
                              <table:table-cell><text:p>StochRsi</text:p></table:table-cell>
                              <table:table-cell><text:p>K</text:p></table:table-cell>
                              <table:table-cell><text:p>D</text:p></table:table-cell>
                            </table:table-row>
                    """.formatted(dateStr));

            for (var r : results) {
                contentBuilder.append(String.format("""
                            <table:table-row>
                              <table:table-cell><text:p>%s</text:p></table:table-cell>
                              <table:table-cell><text:p>%s</text:p></table:table-cell>
                              <table:table-cell><text:p>%s</text:p></table:table-cell>
                              <table:table-cell><text:p>%.2f</text:p></table:table-cell>
                              <table:table-cell><text:p>%.4f</text:p></table:table-cell>
                              <table:table-cell><text:p>%.4f</text:p></table:table-cell>
                              <table:table-cell><text:p>%.4f</text:p></table:table-cell>
                              <table:table-cell><text:p>%.4f</text:p></table:table-cell>
                            </table:table-row>
                        """,
                        escapeXml(r.symbol()),
                        escapeXml(r.interval()),
                        escapeXml(r.time()),
                        r.close(),
                        r.rsi(),
                        r.stochRsi(),
                        r.k(),
                        r.d()
                ));
            }

            contentBuilder.append("""
                          </table:table>
                        </office:text>
                      </office:body>
                    </office:document-content>
                    """);

            zipOut.write(contentBuilder.toString().getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            System.out.println("Successfully generated ODT report: " + odtPath);
        } catch (Exception e) {
            System.err.println("Error generating ODT file: " + e.getMessage());
        }
    }

    private static void generateOds(Path odsPath, String dateStr, List<ResultRow> results) {
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

            // 2. META-INF/manifest.xml
            zipOut.putNextEntry(new ZipEntry("META-INF/manifest.xml"));
            var manifestXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.2">
                      <manifest:file-entry manifest:full-path="/" manifest:media-type="application/vnd.oasis.opendocument.spreadsheet"/>
                      <manifest:file-entry manifest:full-path="META-INF/manifest.xml" manifest:media-type="text/xml"/>
                      <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
                      <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
                      <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
                    </manifest:manifest>
                    """;
            zipOut.write(manifestXml.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // 3. meta.xml
            zipOut.putNextEntry(new ZipEntry("meta.xml"));
            var metaXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-meta xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:dc="http://purl.org/dc/elements/1.1/" office:version="1.2">
                      <office:meta>
                        <dc:creator>CryptoAnalysis</dc:creator>
                        <dc:date>%sT00:00:00Z</dc:date>
                      </office:meta>
                    </office:document-meta>
                    """.formatted(dateStr);
            zipOut.write(metaXml.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // 4. styles.xml
            zipOut.putNextEntry(new ZipEntry("styles.xml"));
            var stylesXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" office:version="1.2">
                      <office:styles/>
                      <office:automatic-styles/>
                      <office:master-styles/>
                    </office:document-styles>
                    """;
            zipOut.write(stylesXml.getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            // 5. content.xml
            zipOut.putNextEntry(new ZipEntry("content.xml"));
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
                            <table:table-column table:number-columns-repeated="8"/>
                            <table:table-row>
                              <table:table-cell><text:p>Symbol</text:p></table:table-cell>
                              <table:table-cell><text:p>Interval</text:p></table:table-cell>
                              <table:table-cell><text:p>Time</text:p></table:table-cell>
                              <table:table-cell><text:p>Close</text:p></table:table-cell>
                              <table:table-cell><text:p>RSI</text:p></table:table-cell>
                              <table:table-cell><text:p>StochRsi</text:p></table:table-cell>
                              <table:table-cell><text:p>K</text:p></table:table-cell>
                              <table:table-cell><text:p>D</text:p></table:table-cell>
                            </table:table-row>
                    """);

            for (var r : results) {
                contentBuilder.append(String.format(java.util.Locale.US, """
                            <table:table-row>
                              <table:table-cell office:value-type="string"><text:p>%s</text:p></table:table-cell>
                              <table:table-cell office:value-type="string"><text:p>%s</text:p></table:table-cell>
                              <table:table-cell office:value-type="string"><text:p>%s</text:p></table:table-cell>
                              <table:table-cell office:value-type="float" office:value="%.2f"><text:p>%.2f</text:p></table:table-cell>
                              <table:table-cell office:value-type="float" office:value="%.4f"><text:p>%.4f</text:p></table:table-cell>
                              <table:table-cell office:value-type="float" office:value="%.4f"><text:p>%.4f</text:p></table:table-cell>
                              <table:table-cell office:value-type="float" office:value="%.4f"><text:p>%.4f</text:p></table:table-cell>
                              <table:table-cell office:value-type="float" office:value="%.4f"><text:p>%.4f</text:p></table:table-cell>
                            </table:table-row>
                        """,
                        escapeXml(r.symbol()),
                        escapeXml(r.interval()),
                        escapeXml(r.time()),
                        r.close(), r.close(),
                        r.rsi(), r.rsi(),
                        r.stochRsi(), r.stochRsi(),
                        r.k(), r.k(),
                        r.d(), r.d()
                ));
            }

            contentBuilder.append("""
                          </table:table>
                        </office:spreadsheet>
                      </office:body>
                    </office:document-content>
                    """);

            zipOut.write(contentBuilder.toString().getBytes(StandardCharsets.UTF_8));
            zipOut.closeEntry();

            System.out.println("Successfully generated ODS/ODF report: " + odsPath);
        } catch (Exception e) {
            System.err.println("Error generating ODS/ODF file: " + e.getMessage());
        }
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
