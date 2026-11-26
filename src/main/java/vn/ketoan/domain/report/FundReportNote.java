package vn.ketoan.domain.report;

import vn.ketoan.domain.MoneyAccountKind;

public record FundReportNote(String code, String name, String accountCode, MoneyAccountKind kind,
                             DebitCredit opening, DebitCredit movements, DebitCredit closing) {
    public FundReportNote {
        code = vn.ketoan.domain.MoneyAccount.normalizeCode(code);
        name = ReportText.required(name, 120, "Fund name");
        accountCode = vn.ketoan.domain.Account.normalizeCode(accountCode);
        java.util.Objects.requireNonNull(kind);
        if (!opening.net().add(movements.net()).equals(closing.net())) { throw new IllegalArgumentException("Fund note does not reconcile"); }
    }
}
