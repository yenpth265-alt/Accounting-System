package vn.ketoan.domain;

public final class RevenueCreator extends VoucherCreator {

    @Override
    protected Voucher createVoucher(Draft draft) {
        return new RevenueVoucher(draft);
    }
}
