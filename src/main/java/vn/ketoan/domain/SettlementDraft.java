package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import vn.ketoan.domain.value.Money;

/** Customer collection or supplier payment, including an advance or an unallocated excess. */
public final class SettlementDraft {

    private final Draft header;
    private final String partnerCode;
    private final String debtAccountCode;
    private final String moneyAccountCode;
    private final Money amount;

    public SettlementDraft(String reference, LocalDate documentDate, LocalDate postingDate, String description,
                           String partnerCode, String debtAccountCode, String moneyAccountCode, Money amount) {
        this.header = new Draft(reference, documentDate, postingDate, description, List.of());
        this.partnerCode = Partner.normalizeCode(partnerCode);
        this.debtAccountCode = Account.normalizeCode(debtAccountCode);
        this.moneyAccountCode = MoneyAccount.normalizeCode(moneyAccountCode);
        this.amount = Objects.requireNonNull(amount, "Settlement amount is required");
        if (!amount.isPositive() || amount.toBigDecimal().precision() > 19) {
            throw new IllegalArgumentException("Settlement amount must be positive and fit DECIMAL(19,0)");
        }
    }

    public Draft toReceiptDraft(String moneyLedgerCode) {
        return header.withLines(List.of(
                VoucherLine.debit(moneyLedgerCode, amount, null, moneyAccountCode),
                VoucherLine.credit(debtAccountCode, amount, partnerCode, null)));
    }

    public Draft toPaymentDraft(String moneyLedgerCode) {
        return header.withLines(List.of(
                VoucherLine.debit(debtAccountCode, amount, partnerCode, null),
                VoucherLine.credit(moneyLedgerCode, amount, null, moneyAccountCode)));
    }

    public String getPartnerCode() {
        return partnerCode;
    }

    public String getDebtAccountCode() {
        return debtAccountCode;
    }

    public String getMoneyAccountCode() {
        return moneyAccountCode;
    }

    public Money getAmount() {
        return amount;
    }
}
