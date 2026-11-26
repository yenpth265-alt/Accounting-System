package vn.ketoan.domain.report;

import java.util.List;
import java.util.Objects;

public record ReportPackage(FinancialStatements financials, GeneralJournal journal, CashFlowStatement cashFlow,
                             ReportNotes notes, List<JournalRow> sourceRows) {
    public ReportPackage {
        Objects.requireNonNull(financials); Objects.requireNonNull(journal); Objects.requireNonNull(cashFlow); Objects.requireNonNull(notes);
        sourceRows = List.copyOf(sourceRows);
        ReportPeriod period = financials.trialBalance().period();
        if (!period.equals(journal.period()) || !period.equals(cashFlow.period()) || !period.equals(notes.period())
                || sourceRows.stream().anyMatch(row -> row.postingDate().isAfter(period.to()))) { throw new IllegalArgumentException("Complete report periods do not match"); }
    }
    public ReportPeriod period() { return journal.period(); }
    public String sourceChecksum(ReportDocument document) {
        if (!period().equals(document.period())) { throw new IllegalArgumentException("Document period does not match source"); }
        var digest = new ReportDigest();
        digest.add(document.fingerprint());
        digest.add(Integer.toString(sourceRows.size()));
        for (var row : sourceRows) {
            for (String value : List.of(row.entryId().toString(), row.documentId().toString(), Integer.toString(row.lineNumber()), row.postingDate().toString(),
                    row.postedAt().toString(), row.reference(), row.description(), row.accountCode(), row.partnerCode() == null ? "" : row.partnerCode(),
                    row.moneyAccountCode() == null ? "" : row.moneyAccountCode(), row.amounts().debit().toString(), row.amounts().credit().toString(), Boolean.toString(row.closing()))) { digest.add(value); }
        }
        return digest.finish();
    }
}
