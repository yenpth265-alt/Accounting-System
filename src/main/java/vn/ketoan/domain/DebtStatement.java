package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.List;

import vn.ketoan.domain.value.Money;

/** Positive net debt is owed; a negative net debt represents an advance or excess settlement. */
public record DebtStatement(String accountCode, String partnerCode, LocalDate asOf, List<OpenItem> items) {

    public DebtStatement {
        accountCode = Account.normalizeCode(accountCode);
        partnerCode = Partner.normalizeCode(partnerCode);
        asOf = DomainValidation.date(asOf, "Statement date");
        items = List.copyOf(items);
        for (OpenItem item : items) {
            if (!item.accountCode().equals(accountCode) || !item.partnerCode().equals(partnerCode)
                    || item.postingDate().isAfter(asOf)) {
                throw new IllegalArgumentException("Statement items must belong to its account, partner and date");
            }
        }
    }

    public List<OpenItem> openItems() {
        return items.stream().filter(item -> item.remaining().isPositive()).toList();
    }

    public Money unsettledDebt() {
        return remaining(OpenItemKind.CHARGE);
    }

    public Money unallocatedSettlements() {
        return remaining(OpenItemKind.SETTLEMENT);
    }

    public Money netDebt() {
        return unsettledDebt().subtract(unallocatedSettlements());
    }

    private Money remaining(OpenItemKind kind) {
        return items.stream().filter(item -> item.kind() == kind).map(OpenItem::remaining).reduce(Money.ZERO, Money::add);
    }
}
