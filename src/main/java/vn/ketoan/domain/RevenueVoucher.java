package vn.ketoan.domain;

import java.util.HashSet;
import java.util.Set;

/** Service revenue on credit for one customer; settlement is a separate voucher. */
public final class RevenueVoucher extends Voucher {

    public RevenueVoucher(Draft draft) {
        super(VoucherType.REVENUE, draft);
    }

    @Override
    protected void validateSpecific(VoucherValidationContext context) {
        requireNoMoneyMovements(context);
        boolean hasRevenue = false;
        Set<String> customers = new HashSet<>();
        for (VoucherLine line : getLines()) {
            Account account = context.requireAccount(line.getAccountCode());
            if (line.getSide() == BalanceSide.DEBIT) {
                if (account.getType() != AccountType.ASSET || account.getTracking() != AccountTracking.PARTNER) {
                    throw new IllegalArgumentException("Revenue debits must be customer receivables");
                }
                String customer = line.getPartnerCode().orElseThrow();
                context.requirePartner(customer).requireRole(PartnerRole.CUSTOMER);
                customers.add(customer);
            } else if (account.getType() == AccountType.REVENUE) {
                hasRevenue = true;
            } else if (account.getType() != AccountType.LIABILITY || account.getTracking() != AccountTracking.NONE) {
                throw new IllegalArgumentException("Revenue credits must be revenue or untracked liabilities");
            }
        }
        if (!hasRevenue || customers.size() != 1) {
            throw new IllegalArgumentException("Revenue requires revenue credit and exactly one customer");
        }
    }
}
