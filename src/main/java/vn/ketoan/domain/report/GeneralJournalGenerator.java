package vn.ketoan.domain.report;

public final class GeneralJournalGenerator implements ReportGenerator<ReportPeriod, GeneralJournal> {
    @Override public GeneralJournal generate(ReportContext context, ReportPeriod period) {
        return new GeneralJournal(period, context.periodsClosed(period), context.rows().stream().filter(row -> period.contains(row.postingDate())).toList());
    }
}
