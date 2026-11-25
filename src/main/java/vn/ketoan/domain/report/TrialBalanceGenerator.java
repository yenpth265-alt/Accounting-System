package vn.ketoan.domain.report;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.value.Money;

public final class TrialBalanceGenerator implements ReportGenerator<ReportPeriod, TrialBalance> {
    @Override public TrialBalance generate(ReportContext context, ReportPeriod period) {
        Map<String, DebitCredit> movements = new HashMap<>();
        Map<Key, Money> opening = new HashMap<>();
        Map<Key, Money> closing = new HashMap<>();
        for (JournalRow row : context.rows()) {
            if (row.postingDate().isAfter(period.to())) { continue; }
            Account account = context.requireAccount(row.accountCode());
            boolean gross = account.getTracking() == AccountTracking.PARTNER
                    && (account.getType() == AccountType.ASSET || account.getType() == AccountType.LIABILITY);
            Key key = new Key(row.accountCode(), gross ? row.partnerCode() : null);
            closing.merge(key, row.net(), Money::add);
            if (row.postingDate().isBefore(period.from())) { opening.merge(key, row.net(), Money::add); }
            else { movements.merge(row.accountCode(), row.amounts(), DebitCredit::add); }
        }
        var rows = new ArrayList<TrialBalanceRow>();
        Map<String, DebitCredit> openingTotals = group(opening);
        Map<String, DebitCredit> closingTotals = group(closing);
        for (Account account : context.postingAccounts()) {
            rows.add(new TrialBalanceRow(account.getCode(), account.getName(), account.getType(),
                    openingTotals.getOrDefault(account.getCode(), DebitCredit.ZERO), movements.getOrDefault(account.getCode(), DebitCredit.ZERO),
                    closingTotals.getOrDefault(account.getCode(), DebitCredit.ZERO)));
        }
        return new TrialBalance(period, context.periodsClosed(period), rows);
    }
    private Map<String, DebitCredit> group(Map<Key, Money> balances) {
        Map<String, DebitCredit> totals = new HashMap<>();
        balances.forEach((key, net) -> totals.merge(key.account(), DebitCredit.fromNet(net), DebitCredit::add));
        return totals;
    }
    private record Key(String account, String partner) { }
}
