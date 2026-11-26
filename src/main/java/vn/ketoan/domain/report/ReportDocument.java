package vn.ketoan.domain.report;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Memento content: a deep-immutable document whose values never depend on a later database read. */
public record ReportDocument(ReportPeriod period, List<ReportTable> tables) {
    public ReportDocument {
        Objects.requireNonNull(period);
        tables = List.copyOf(tables);
        var codes = new HashSet<String>();
        if (tables.isEmpty() || tables.size() > 32) { throw new IllegalArgumentException("Invalid report document table count"); }
        for (var table : tables) { if (!codes.add(table.code())) { throw new IllegalArgumentException("Report table codes must be unique"); } }
    }
    public String fingerprint() {
        var digest = new ReportDigest();
        digest.add("ACCOUNTING_REPORT_DOCUMENT_V1");
        digest.add(period.from().toString()); digest.add(period.to().toString()); digest.add(Integer.toString(tables.size()));
        for (var table : tables) {
            digest.add(table.code()); digest.add(table.title()); digest.add(Integer.toString(table.columns().size()));
            table.columns().forEach(digest::add);
            digest.add(Integer.toString(table.rows().size()));
            table.rows().forEach(row -> row.forEach(digest::add));
        }
        return digest.finish();
    }
}
