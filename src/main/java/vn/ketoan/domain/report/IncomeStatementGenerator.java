package vn.ketoan.domain.report;

import java.util.HashMap;
import java.util.Map;
import vn.ketoan.domain.AccountType;

public final class IncomeStatementGenerator implements ReportGenerator<ReportPeriod, IncomeStatement> {
    @Override public IncomeStatement generate(ReportContext context, ReportPeriod period) {
        Map<String, DebitCredit> amounts = new HashMap<>();
        for (JournalRow row : context.rows()) {
            if (row.closing() || !period.contains(row.postingDate())) { continue; }
            AccountType type = context.requireAccount(row.accountCode()).getType();
            if (type == AccountType.REVENUE || type == AccountType.EXPENSE) { amounts.merge(row.accountCode(), row.amounts(), DebitCredit::add); }
        }
        return new IncomeStatement(period, context.periodsClosed(period), context.postingAccounts().stream()
                .filter(account -> amounts.containsKey(account.getCode()))
                .map(account -> new IncomeStatementLine(account.getCode(), account.getName(), account.getType(), amounts.get(account.getCode()))).toList());
    }
}
