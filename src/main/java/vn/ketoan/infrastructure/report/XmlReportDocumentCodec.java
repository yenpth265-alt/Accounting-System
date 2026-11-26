package vn.ketoan.infrastructure.report;

import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import vn.ketoan.application.port.ReportDocumentCodec;
import vn.ketoan.domain.report.ReportDocument;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.ReportTable;

/** Versioned XML for generic tables; external entities and DTDs are forbidden. */
public final class XmlReportDocumentCodec implements ReportDocumentCodec {
    private static final int MAX_LENGTH = 4_000_000;
    @Override public String encode(ReportDocument document) {
        try {
            var output = new StringWriter();
            var writer = XMLOutputFactory.newFactory().createXMLStreamWriter(output);
            writer.writeStartDocument("UTF-8", "1.0");
            writer.writeStartElement("report"); writer.writeAttribute("format", "1");
            writer.writeAttribute("from", document.period().from().toString()); writer.writeAttribute("to", document.period().to().toString());
            for (var table : document.tables()) {
                writer.writeStartElement("table"); writer.writeAttribute("code", table.code());
                writer.writeStartElement("title"); writer.writeCharacters(table.title()); writer.writeEndElement();
                writer.writeStartElement("columns");
                for (String column : table.columns()) { writer.writeStartElement("column"); writer.writeCharacters(column); writer.writeEndElement(); }
                writer.writeEndElement(); writer.writeStartElement("rows");
                for (var row : table.rows()) {
                    writer.writeStartElement("row");
                    for (String cell : row) { writer.writeStartElement("cell"); writer.writeCharacters(cell); writer.writeEndElement(); }
                    writer.writeEndElement();
                }
                writer.writeEndElement(); writer.writeEndElement();
            }
            writer.writeEndElement(); writer.writeEndDocument(); writer.close();
            String result = output.toString();
            if (result.length() > MAX_LENGTH) { throw new IllegalArgumentException("Report snapshot exceeds the supported size"); }
            return result;
        } catch (XMLStreamException failure) { throw new IllegalArgumentException("Cannot encode report document", failure); }
    }
    @Override public ReportDocument decode(String payload) {
        if (payload == null || payload.length() > MAX_LENGTH) { throw new IllegalArgumentException("Invalid report snapshot size"); }
        var factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setXMLResolver((publicId, systemId, base, namespace) -> { throw new XMLStreamException("External XML access is disabled"); });
        XMLStreamReader reader = null;
        try {
            reader = factory.createXMLStreamReader(new StringReader(payload));
            expect(reader, reader.nextTag(), XMLStreamConstants.START_ELEMENT, "report");
            if (!"1".equals(reader.getAttributeValue(null, "format"))) { throw new IllegalArgumentException("Unsupported report document format"); }
            var period = new ReportPeriod(LocalDate.parse(attribute(reader, "from")), LocalDate.parse(attribute(reader, "to")));
            var tables = new ArrayList<ReportTable>();
            while (reader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                expect(reader, reader.getEventType(), XMLStreamConstants.START_ELEMENT, "table");
                String code = attribute(reader, "code");
                String title = reader.getAttributeValue(null, "title");
                int next = reader.nextTag();
                if (title == null) {
                    expect(reader, next, XMLStreamConstants.START_ELEMENT, "title");
                    title = reader.getElementText();
                    next = reader.nextTag();
                }
                expect(reader, next, XMLStreamConstants.START_ELEMENT, "columns");
                var columns = new ArrayList<String>();
                while (reader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                    expect(reader, reader.getEventType(), XMLStreamConstants.START_ELEMENT, "column");
                    columns.add(reader.getElementText());
                    if (columns.size() > 32) { throw new IllegalArgumentException("Too many report columns"); }
                }
                expect(reader, reader.getEventType(), XMLStreamConstants.END_ELEMENT, "columns");
                expect(reader, reader.nextTag(), XMLStreamConstants.START_ELEMENT, "rows");
                var rows = new ArrayList<List<String>>();
                while (reader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                    expect(reader, reader.getEventType(), XMLStreamConstants.START_ELEMENT, "row");
                    var cells = new ArrayList<String>();
                    while (reader.nextTag() == XMLStreamConstants.START_ELEMENT) {
                        expect(reader, reader.getEventType(), XMLStreamConstants.START_ELEMENT, "cell");
                        cells.add(reader.getElementText());
                        if (cells.size() > 32) { throw new IllegalArgumentException("Too many report cells"); }
                    }
                    expect(reader, reader.getEventType(), XMLStreamConstants.END_ELEMENT, "row");
                    rows.add(cells);
                    if (rows.size() > 50_000) { throw new IllegalArgumentException("Too many report rows"); }
                }
                expect(reader, reader.getEventType(), XMLStreamConstants.END_ELEMENT, "rows");
                expect(reader, reader.nextTag(), XMLStreamConstants.END_ELEMENT, "table");
                tables.add(new ReportTable(code, title, columns, rows));
                if (tables.size() > 32) { throw new IllegalArgumentException("Too many report tables"); }
            }
            expect(reader, reader.getEventType(), XMLStreamConstants.END_ELEMENT, "report");
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT || event == XMLStreamConstants.DTD || (reader.isCharacters() && !reader.isWhiteSpace())) {
                    throw new IllegalArgumentException("Unexpected content after report document");
                }
            }
            return new ReportDocument(period, tables);
        } catch (XMLStreamException failure) { throw new IllegalArgumentException("Invalid stored report XML", failure); }
        finally { if (reader != null) { try { reader.close(); } catch (XMLStreamException ignored) { } } }
    }
    private String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null) { throw new IllegalArgumentException("Missing report XML attribute: " + name); }
        return value;
    }
    private void expect(XMLStreamReader reader, int event, int required, String name) {
        if (event != required || !name.equals(reader.getLocalName()) || (reader.getNamespaceURI() != null && !reader.getNamespaceURI().isEmpty())) {
            throw new IllegalArgumentException("Unexpected report XML structure; expected " + name);
        }
    }
}
