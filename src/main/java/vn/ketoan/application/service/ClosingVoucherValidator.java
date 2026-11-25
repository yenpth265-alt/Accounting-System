package vn.ketoan.application.service;

import java.util.Set;
import java.util.stream.Collectors;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.PeriodStatus;
import vn.ketoan.domain.Voucher;
import vn.ketoan.domain.VoucherSnapshot;
import vn.ketoan.domain.VoucherValidationContext;

/** A narrow capability for the already reserved, frozen closing content. Ordinary validators remain strict. */
final class ClosingVoucherValidator implements VoucherValidator {
    private final AccountingSession session;
    private final ClosingBatch batch;
    private final Set<VoucherSnapshot> allowed;
    ClosingVoucherValidator(AccountingSession session, ClosingBatch batch) {
        this.session = session;
        this.batch = batch;
        allowed = batch.documentIds().stream().map(id -> VoucherSnapshot.from(session.documents().findById(id).orElseThrow().getContent()))
                .collect(Collectors.toUnmodifiableSet());
    }
    @Override public void validate(Voucher voucher) {
        var period = session.periods().findByMonth(batch.request().month()).orElseThrow();
        if (period.getStatus() != PeriodStatus.CLOSING || !allowed.contains(VoucherSnapshot.from(voucher))) {
            throw new IllegalStateException("Only this batch's frozen closing vouchers may use its closing period");
        }
        // Reuse all normal voucher checks after proving the closing capability above.
        voucher.validateForSubmission(new VoucherValidationContext(session.accounts().findAll(), session.partners().findAll(),
                session.moneyAccounts().findAll(), AccountingPeriod.open(batch.request().month())));
    }
}
