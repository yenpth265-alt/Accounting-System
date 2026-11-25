package vn.ketoan.domain.report;

import java.util.Objects;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.Partner;

public record LedgerQuery(ReportPeriod period, String accountCode, String partnerCode, String moneyAccountCode) {
    public LedgerQuery {
        Objects.requireNonNull(period, "Ledger period is required");
        accountCode = Account.normalizeCode(accountCode);
        partnerCode = partnerCode == null ? null : Partner.normalizeCode(partnerCode);
        moneyAccountCode = moneyAccountCode == null ? null : MoneyAccount.normalizeCode(moneyAccountCode);
    }
    public static LedgerQuery account(ReportPeriod period, String code) { return new LedgerQuery(period, code, null, null); }
}
