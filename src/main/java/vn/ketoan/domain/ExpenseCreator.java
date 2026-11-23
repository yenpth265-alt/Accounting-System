package vn.ketoan.domain;

public final class ExpenseCreator extends VoucherCreator {

    @Override
    protected Voucher createVoucher(Draft draft) {
        return new ExpenseVoucher(draft);
    }
}
