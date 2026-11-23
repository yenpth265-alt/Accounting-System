package vn.ketoan.domain;

public final class PaymentVoucher extends CashVoucher {

    public PaymentVoucher(Draft draft) {
        super(VoucherType.PAYMENT, draft, BalanceSide.CREDIT);
    }
}
