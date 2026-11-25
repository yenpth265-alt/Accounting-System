package vn.ketoan.domain.report;

import java.util.Objects;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.value.Money;

/** Null account code denotes the computed, unclosed result within equity. */
public record BalanceSheetLine(String accountCode, String accountName, String partnerCode,
                               BalanceSheetSection section, Money amount, boolean reclassified) {
    public BalanceSheetLine {
        Objects.requireNonNull(accountName, "Balance sheet line name is required");
        Objects.requireNonNull(section, "Balance sheet section is required");
        Objects.requireNonNull(amount, "Balance sheet amount is required");
        accountCode = accountCode == null ? null : Account.normalizeCode(accountCode);
        partnerCode = partnerCode == null ? null : Partner.normalizeCode(partnerCode);
        if ((accountCode == null && (section != BalanceSheetSection.EQUITY || partnerCode != null || reclassified))
                || (reclassified && (partnerCode == null || amount.isNegative() || section == BalanceSheetSection.EQUITY))) {
            throw new IllegalArgumentException("Invalid balance sheet presentation");
        }
    }
}
