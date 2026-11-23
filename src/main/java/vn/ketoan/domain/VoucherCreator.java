package vn.ketoan.domain;

import java.util.Objects;

/** Factory Method: concrete creators decide which voucher subclass to instantiate. */
public abstract class VoucherCreator {

    public final Voucher create(Draft draft) {
        Objects.requireNonNull(draft, "Draft is required");
        return Objects.requireNonNull(createVoucher(draft), "A creator must return a voucher");
    }

    protected abstract Voucher createVoucher(Draft draft);
}
