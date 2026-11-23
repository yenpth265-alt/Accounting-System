package vn.ketoan.domain;

public final class ReceiptVoucher extends CashVoucher {

    public ReceiptVoucher(Draft draft) {
        super(VoucherType.RECEIPT, draft, BalanceSide.DEBIT);
    }
}
