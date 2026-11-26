package vn.ketoan.domain.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.CorrectionBook;
import vn.ketoan.domain.PrepaidBook;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;

public final class ReportNotesGenerator implements ReportGenerator<ReportPeriod, ReportNotes> {
    @Override public ReportNotes generate(ReportContext context, ReportPeriod period) {
        var disclosures = new ArrayList<String>();
        disclosures.add("Currency: VND; whole dong. Business posting dates define the reporting period.");
        disclosures.add("Only posted entries contribute to amounts. Income excludes generated closing transfers; adjustments and reversals remain included.");
        disclosures.add("Partner balances are shown separately. Receivable credit balances and payable debit balances are presented as advances.");
        disclosures.add("Cash flow activities require explicit classification. Pure transfers between company funds do not create external cash flows.");
        if (!context.periodsClosed(period)) { disclosures.add("Provisional: source accounting periods are not all closed."); }
        long pending = context.evidence().documents().stream().filter(document -> period.contains(document.getContent().getPostingDate())
                && document.getStatus() != VoucherStatus.POSTED && document.getStatus() != VoucherStatus.CANCELLED).count();
        disclosures.add("Unposted vouchers in this period: " + pending + ".");
        var cash = new CashFlowGenerator().generate(context, period);
        if (!cash.complete()) { disclosures.add("Unclassified cash flows remain; this report cannot be published."); }
        List<FundReportNote> funds = context.funds().stream().map(fund -> {
            var amounts = balances(context.rows().stream().filter(row -> fund.getCode().equals(row.moneyAccountCode())).toList(), period);
            return new FundReportNote(fund.getCode(), fund.getName(), fund.getLedgerAccountCode(), fund.getKind(), amounts.opening(), amounts.movements(), amounts.closing());
        }).toList();
        var partners = new ArrayList<PartnerReportNote>();
        var partnerNames = context.partners().stream().collect(Collectors.toMap(vn.ketoan.domain.Partner::getCode, vn.ketoan.domain.Partner::getName));
        var keys = context.rows().stream().filter(row -> !row.postingDate().isAfter(period.to()))
                .filter(row -> context.requireAccount(row.accountCode()).getTracking() == AccountTracking.PARTNER)
                .filter(row -> { var type = context.requireAccount(row.accountCode()).getType(); return type == AccountType.ASSET || type == AccountType.LIABILITY; })
                .map(row -> new Key(row.accountCode(), row.partnerCode())).distinct().sorted(Comparator.comparing(Key::account).thenComparing(Key::partner)).toList();
        for (Key key : keys) {
            var amounts = balances(context.rows().stream().filter(row -> row.accountCode().equals(key.account()) && key.partner().equals(row.partnerCode())).toList(), period);
            partners.add(new PartnerReportNote(key.account(), context.requireAccount(key.account()).getName(), key.partner(), partnerNames.get(key.partner()), amounts.opening(), amounts.movements(), amounts.closing()));
        }
        var evidence = context.evidence();
        var docs = evidence.documents().stream().collect(Collectors.toMap(VoucherDocument::getId, Function.identity()));
        var journal = context.journal().stream().collect(Collectors.toMap(vn.ketoan.domain.JournalEntry::getId, Function.identity()));
        var prepaid = new ArrayList<PrepaidReportNote>();
        if (!evidence.prepaidPlans().isEmpty()) {
            var book = new PrepaidBook(context.accounts(), evidence.documents(), context.journal(), evidence.prepaidPlans(), evidence.prepaidCancellations(), evidence.prepaidAllocations(),
                    new CorrectionBook(context.accounts(), evidence.documents(), context.journal(), evidence.corrections()));
            for (var plan : evidence.prepaidPlans().stream().sorted(Comparator.comparing(vn.ketoan.domain.PrepaidExpensePlan::id)).toList()) {
                if (journal.get(plan.request().source().journalEntryId()).getPostingDate().isAfter(period.to())) { continue; }
                var end = book.statement(plan.id(), period.to());
                Money previous = period.from().getYear() == 1000 && period.from().getDayOfYear() == 1 ? Money.ZERO : book.statement(plan.id(), period.from().minusDays(1)).recognized();
                prepaid.add(new PrepaidReportNote(plan.id(), plan.request().reference(), end.original(), end.recognized().subtract(previous), end.recognized(), end.remainingAsset(), end.cancelled()));
            }
        }
        var corrections = evidence.corrections().stream().filter(record -> docs.get(record.targetDocumentId()).getStatus() == VoucherStatus.POSTED
                        && period.contains(docs.get(record.targetDocumentId()).getContent().getPostingDate()))
                .sorted(Comparator.comparing(record -> docs.get(record.targetDocumentId()).getContent().getReference()))
                .map(record -> new CorrectionReportNote(docs.get(record.sourceDocumentId()).getContent().getReference(), docs.get(record.targetDocumentId()).getContent().getReference(),
                        record.kind(), docs.get(record.targetDocumentId()).getContent().getPostingDate(), record.reason())).toList();
        var cashBook = new CashFlowBook(context.accounts(), context.journal(), evidence.corrections(), evidence.cashClassifications());
        Map<UUID, CashFlowClassification> used = new HashMap<>();
        context.journal().stream().filter(entry -> period.contains(entry.getPostingDate()) && !cashBook.totals(entry).amounts().equals(DebitCredit.ZERO)).forEach(entry -> {
            var basis = cashBook.breakdown(entry.getId()).basis();
            if (basis != null) { used.put(basis.id(), basis); }
        });
        return new ReportNotes(period, disclosures, funds, partners, prepaid, corrections,
                used.values().stream().sorted(Comparator.comparing(value -> value.id().toString())).toList());
    }
    private Amounts balances(List<JournalRow> rows, ReportPeriod period) {
        Money opening = Money.ZERO;
        DebitCredit movements = DebitCredit.ZERO;
        for (var row : rows) {
            if (row.postingDate().isBefore(period.from())) { opening = opening.add(row.net()); }
            else if (period.contains(row.postingDate())) { movements = movements.add(row.amounts()); }
        }
        return new Amounts(DebitCredit.fromNet(opening), movements, DebitCredit.fromNet(opening.add(movements.net())));
    }
    private record Key(String account, String partner) { }
    private record Amounts(DebitCredit opening, DebitCredit movements, DebitCredit closing) { }
}
