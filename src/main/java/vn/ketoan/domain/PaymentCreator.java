package vn.ketoan.domain;

public final class PaymentCreator extends VoucherCreator {

    @Override
    protected Voucher createVoucher(Draft draft) {
        return new PaymentVoucher(draft);
    }
}
