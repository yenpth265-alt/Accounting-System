package vn.ketoan.infrastructure.report;

import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.Test;
import vn.ketoan.domain.report.ReportDocument;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.ReportTable;
import static org.junit.jupiter.api.Assertions.*;

class XmlReportDocumentCodecTest {
    private final XmlReportDocumentCodec codec = new XmlReportDocumentCodec();
    @Test void unicodeEscapingEmptyCellsAndLineEndingsRoundTripWithoutChangingTheFingerprint() {
        var document = new ReportDocument(ReportPeriod.month(YearMonth.of(2026, 11)), List.of(new ReportTable("NOTES", "Thuyết minh & <đối chiếu>\n\tDòng phụ",
                List.of("Mục", "Số liệu"), List.of(List.of("Khách A & B < 10", ""), List.of("Dòng\r\nmới", "9000000000000000000")))));
        var restored = codec.decode(codec.encode(document));
        assertEquals(document, restored);
        assertEquals(document.fingerprint(), restored.fingerprint());
    }
    @Test void dtdsAndExternalEntitiesAreRejected() {
        String xml = "<!DOCTYPE report [<!ENTITY secret SYSTEM 'file:///not-a-real-report-file'>]><report format='1' from='2026-11-01' to='2026-11-30'><table code='X' title='X'><columns><column>X</column></columns><rows><row><cell>&secret;</cell></row></rows></table></report>";
        assertThrows(IllegalArgumentException.class, () -> codec.decode(xml));
    }
    @Test void unknownFormatsUnexpectedElementsAndMissingColumnsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> codec.decode("<report format='2' from='2026-11-01' to='2026-11-30'/>"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("<report format='1' from='2026-11-01' to='2026-11-30'><unexpected/></report>"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode("<report format='1' from='2026-11-01' to='2026-11-30'><table code='X' title='X'><columns/><rows/></table></report>"));
    }
    @Test void reportTablesDefensivelyCopyDataAndRejectMismatchedCells() {
        assertThrows(IllegalArgumentException.class, () -> new ReportTable("X", "Title", List.of("A"), List.of(List.of("1", "2"))));
        assertThrows(IllegalArgumentException.class, () -> new ReportTable("X", "Title", List.of("A"), List.of(List.of("bad\u0000text"))));
    }
    @Test void earlierAttributeBasedTitlesRemainReadableWithoutChangingTheirDocumentFingerprint() {
        String payload = "<report format='1' from='2026-11-01' to='2026-11-30'><table code='X' title='Old title'><columns><column>A</column></columns><rows><row><cell>7</cell></row></rows></table></report>";
        var document = codec.decode(payload);
        assertEquals("Old title", document.tables().getFirst().title());
        assertEquals(document, codec.decode(codec.encode(document)));
    }
}
