package vn.ketoan.application.service;

import java.util.Objects;

import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.Voucher;
import vn.ketoan.domain.VoucherCreator;

/** Creation and readiness validation only; no approval, storage or ledger posting. */
public final class VoucherCreationService {

    private final VoucherCreator creator;
    private final VoucherValidator validator;

    public VoucherCreationService(VoucherCreator creator, VoucherValidator validator) {
        this.creator = Objects.requireNonNull(creator, "Voucher creator is required");
        this.validator = Objects.requireNonNull(validator, "Voucher validator is required");
    }

    public Voucher create(Draft draft) {
        return creator.create(draft);
    }

    public Voucher createValidated(Draft draft) {
        Voucher voucher = create(draft);
        validator.validate(voucher);
        return voucher;
    }
}
