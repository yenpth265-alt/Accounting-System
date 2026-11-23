package vn.ketoan.domain;

import java.util.Objects;

/** Shared settlement rules for receipts and payments. */
public abstract class CashVoucher extends Voucher {

    private final BalanceSide moneySide;

    protected CashVoucher(VoucherType type, Draft draft, BalanceSide moneySide) {
        super(type, draft);
        this.moneySide = Objects.requireNonNull(moneySide, "Money side is required");
    }

    @Override
    protected final void validateSpecific(VoucherValidationContext context) {
        boolean hasMoney = false;
        for (VoucherLine line : getLines()) {
            Account account = context.requireAccount(line.getAccountCode());
            if (account.getTracking() == AccountTracking.MONEY_ACCOUNT) {
                hasMoney = true;
                if (line.getSide() != moneySide) {
                    throw new IllegalArgumentException("Money must be on the " + moneySide + " side for " + getType());
                }
            } else {
                if (line.getSide() == moneySide) {
                    throw new IllegalArgumentException("Settlement counterparts must oppose the money side");
                }
                if (account.getType() == AccountType.REVENUE || account.getType() == AccountType.EXPENSE
                        || account.getType() == AccountType.RESULT) {
                    throw new IllegalArgumentException("Recognize income or expense separately before settlement");
                }
                if (account.getTracking() == AccountTracking.PARTNER) {
                    Partner partner = context.requirePartner(line.getPartnerCode().orElseThrow());
                    if (account.getType() == AccountType.ASSET) {
                        partner.requireRole(PartnerRole.CUSTOMER);
                    } else if (account.getType() == AccountType.LIABILITY) {
                        partner.requireRole(PartnerRole.SUPPLIER);
                    } else {
                        throw new IllegalArgumentException("Settlement partner accounts must be assets or liabilities");
                    }
                }
            }
        }
        if (!hasMoney) {
            throw new IllegalArgumentException("A receipt or payment requires a money movement");
        }
    }
}
