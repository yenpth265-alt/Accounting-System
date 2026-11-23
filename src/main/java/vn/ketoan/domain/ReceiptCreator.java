package vn.ketoan.domain;

public final class ReceiptCreator extends VoucherCreator {

    @Override
    protected Voucher createVoucher(Draft draft) {
        return new ReceiptVoucher(draft);
    }
}
