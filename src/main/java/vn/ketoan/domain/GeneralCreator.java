package vn.ketoan.domain;

public final class GeneralCreator extends VoucherCreator {

    @Override
    protected Voucher createVoucher(Draft draft) {
        return new GeneralVoucher(draft);
    }
}
