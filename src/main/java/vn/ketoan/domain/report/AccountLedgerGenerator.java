package vn.ketoan.domain.report;

import java.util.ArrayList;
import java.util.Objects;
import vn.ketoan.domain.value.Money;

public final class AccountLedgerGenerator implements ReportGenerator<LedgerQuery, AccountLedger> {
    @Override public AccountLedger generate(ReportContext context, LedgerQuery query) {
        var family = context.accountFamily(query.accountCode());
        context.requireFilters(query, family);
        var selected = context.rows().stream().filter(row -> family.contains(row.accountCode()))
                .filter(row -> query.partnerCode() == null || Objects.equals(query.partnerCode(), row.partnerCode()))
                .filter(row -> query.moneyAccountCode() == null || Objects.equals(query.moneyAccountCode(), row.moneyAccountCode())).toList();
        Money opening = selected.stream().filter(row -> row.postingDate().isBefore(query.period().from())).map(JournalRow::net).reduce(Money.ZERO, Money::add);
        Money current = opening;
        var movements = new ArrayList<LedgerMovement>();
        for (JournalRow row : selected) {
            if (query.period().contains(row.postingDate())) {
                current = current.add(row.net());
                movements.add(new LedgerMovement(row, current));
            }
        }
        return new AccountLedger(query, context.periodsClosed(query.period()), opening, movements);
    }
}
