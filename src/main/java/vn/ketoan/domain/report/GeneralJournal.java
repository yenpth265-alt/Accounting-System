package vn.ketoan.domain.report;

import java.util.List;
import java.util.Objects;

public record GeneralJournal(ReportPeriod period, boolean periodsClosed, List<JournalRow> rows) {
    public GeneralJournal {
        Objects.requireNonNull(period, "Journal period is required");
        rows = List.copyOf(rows);
        if (rows.stream().anyMatch(row -> !period.contains(row.postingDate()))
                || !rows.stream().map(JournalRow::amounts).reduce(DebitCredit.ZERO, DebitCredit::add).balanced()) {
            throw new IllegalArgumentException("General journal dates and debit/credit totals must be consistent");
        }
    }
    public DebitCredit totals() { return rows.stream().map(JournalRow::amounts).reduce(DebitCredit.ZERO, DebitCredit::add); }
    public long entryCount() { return rows.stream().map(JournalRow::entryId).distinct().count(); }
}
