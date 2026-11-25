package vn.ketoan.domain;

import java.util.HashSet;
import java.util.Set;

/** Expense recognition on credit for one supplier, including related asset/tax lines. */
public final class ExpenseVoucher extends Voucher {

    public ExpenseVoucher(Draft draft) {
        super(VoucherType.EXPENSE, draft);
    }

    @Override
    protected void validateSpecific(VoucherValidationContext context) {
        requireNoMoneyMovements(context);
        boolean hasExpense = false;
        Set<String> suppliers = new HashSet<>();
        for (VoucherLine line : getLines()) {
            Account account = context.requireAccount(line.getAccountCode());
            if (line.getSide() == BalanceSide.CREDIT) {
                if (account.getType() != AccountType.LIABILITY || account.getTracking() != AccountTracking.PARTNER) {
                    throw new IllegalArgumentException("Expense credits must be supplier payables");
                }
                String supplier = line.getPartnerCode().orElseThrow();
                context.requirePartner(supplier).requireRole(PartnerRole.SUPPLIER);
                suppliers.add(supplier);
            } else {
                if (account.getTracking() != AccountTracking.NONE || line.getPartnerCode().isPresent()) {
                    throw new IllegalArgumentException("Expense debit components cannot track counterparties");
                }
                if (account.getType() == AccountType.EXPENSE) {
                    hasExpense = true;
                } else if (account.getType() != AccountType.ASSET) {
                    throw new IllegalArgumentException("Expense debits must be expenses or untracked assets");
                }
            }
        }
        if (!hasExpense || suppliers.size() != 1) {
            throw new IllegalArgumentException("Expense requires expense debit and exactly one supplier");
        }
    }
}
