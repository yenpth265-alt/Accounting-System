package vn.ketoan.domain.report;

import java.util.ArrayList;
import java.util.Comparator;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.value.Money;

public final class CashFlowGenerator implements ReportGenerator<ReportPeriod, CashFlowStatement> {
    @Override public CashFlowStatement generate(ReportContext context, ReportPeriod period) {
        var book = new CashFlowBook(context.accounts(), context.journal(), context.evidence().corrections(), context.evidence().cashClassifications());
        Money opening = context.rows().stream().filter(row -> row.postingDate().isBefore(period.from()))
                .filter(row -> context.requireAccount(row.accountCode()).getTracking() == AccountTracking.MONEY_ACCOUNT).map(JournalRow::net).reduce(Money.ZERO, Money::add);
        Money closing = context.rows().stream().filter(row -> !row.postingDate().isAfter(period.to()))
                .filter(row -> context.requireAccount(row.accountCode()).getTracking() == AccountTracking.MONEY_ACCOUNT).map(JournalRow::net).reduce(Money.ZERO, Money::add);
        var rows = new ArrayList<CashFlowLine>();
        var transfers = new ArrayList<CashTransfer>();
        for (JournalEntry entry : context.journal().stream().filter(value -> period.contains(value.getPostingDate()))
                .sorted(Comparator.comparing(JournalEntry::getPostingDate).thenComparing(JournalEntry::getPostedAt)
                        .thenComparing(value -> value.getContent().reference()).thenComparing(value -> value.getId().toString())).toList()) {
            if (book.totals(entry).amounts().equals(DebitCredit.ZERO)) { continue; }
            var breakdown = book.breakdown(entry.getId());
            for (CashFlowSplit split : breakdown.splits()) {
                rows.add(new CashFlowLine(entry.getId(), entry.getContent().reference(), entry.getPostingDate(), split.activity(), split.direction(), split.amount(), breakdown.reversal()));
            }
            if (breakdown.internalTransfer().isPositive()) { transfers.add(new CashTransfer(entry.getId(), entry.getContent().reference(), entry.getPostingDate(), breakdown.internalTransfer())); }
        }
        return new CashFlowStatement(period, context.periodsClosed(period), opening, closing, rows, transfers);
    }
}
