package vn.ketoan.domain;

import java.util.HashMap;
import java.util.Map;

import vn.ketoan.domain.value.Money;

/** General entries may reclassify partners/funds, but must have a nonzero accounting effect. */
public final class GeneralVoucher extends Voucher {

    public GeneralVoucher(Draft draft) {
        super(VoucherType.GENERAL, draft);
    }

    @Override
    protected void validateSpecific(VoucherValidationContext context) {
        Map<PostingKey, Money> movements = new HashMap<>();
        for (VoucherLine line : getLines()) {
            PostingKey key = new PostingKey(line.getAccountCode(),
                    line.getPartnerCode().orElse(""), line.getMoneyAccountCode().orElse(""));
            Money signed = line.getSide() == BalanceSide.DEBIT ? line.getAmount() : line.getAmount().negate();
            movements.merge(key, signed, Money::add);
        }
        if (movements.values().stream().allMatch(Money::isZero)) {
            throw new IllegalArgumentException("A general voucher must change an account or tracked detail");
        }
    }

    private record PostingKey(String accountCode, String partnerCode, String moneyAccountCode) {
    }
}
