package vn.ketoan.domain.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.value.Money;

public final class BalanceSheetGenerator implements ReportGenerator<LocalDate, BalanceSheet> {
    @Override public BalanceSheet generate(ReportContext context, LocalDate asOf) {
        ReportPeriod.requireDate(asOf);
        Map<Key, Money> balances = new HashMap<>();
        for (JournalRow row : context.rows()) {
            if (row.postingDate().isAfter(asOf)) { continue; }
            Account account = context.requireAccount(row.accountCode());
            balances.merge(new Key(row.accountCode(), account.getTracking() == AccountTracking.PARTNER ? row.partnerCode() : null), row.net(), Money::add);
        }
        var rows = new ArrayList<BalanceSheetLine>();
        Money unclosed = Money.ZERO;
        var keys = balances.keySet().stream().sorted(Comparator.comparing(Key::account).thenComparing(Key::partner, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
        for (Key key : keys) {
            Money net = balances.get(key);
            if (net.isZero()) { continue; }
            Account account = context.requireAccount(key.account());
            if (account.getType() == AccountType.REVENUE || account.getType() == AccountType.EXPENSE || account.getType() == AccountType.RESULT) {
                unclosed = unclosed.subtract(net);
                continue;
            }
            BalanceSheetSection section;
            Money amount;
            boolean reclassified = false;
            if (account.getType() == AccountType.ASSET) {
                reclassified = account.getTracking() == AccountTracking.PARTNER && net.isNegative();
                section = reclassified ? BalanceSheetSection.LIABILITY : BalanceSheetSection.ASSET;
                amount = reclassified ? net.negate() : net;
            } else {
                reclassified = account.getType() == AccountType.LIABILITY && account.getTracking() == AccountTracking.PARTNER && net.isPositive();
                section = account.getType() == AccountType.EQUITY ? BalanceSheetSection.EQUITY
                        : reclassified ? BalanceSheetSection.ASSET : BalanceSheetSection.LIABILITY;
                amount = reclassified ? net : net.negate();
            }
            rows.add(new BalanceSheetLine(account.getCode(), account.getName(), key.partner(), section, amount, reclassified));
        }
        if (!unclosed.isZero()) { rows.add(new BalanceSheetLine(null, "Unclosed profit/loss", null, BalanceSheetSection.EQUITY, unclosed, false)); }
        rows.sort(Comparator.comparing(BalanceSheetLine::section)
                .thenComparing(BalanceSheetLine::accountCode, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(BalanceSheetLine::partnerCode, Comparator.nullsFirst(Comparator.naturalOrder())));
        return new BalanceSheet(asOf, context.periodsClosed(new ReportPeriod(asOf, asOf)), rows);
    }
    private record Key(String account, String partner) { }
}
