package vn.ketoan.application.service;

import java.time.YearMonth;

import org.junit.jupiter.api.Test;

import vn.ketoan.VoucherTestData.Stores;
import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.GeneralVoucher;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.ReceiptVoucher;
import vn.ketoan.domain.Voucher;
import vn.ketoan.infrastructure.memory.InMemoryAccountingPeriodRepository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.VoucherTestData.draft;
import static vn.ketoan.VoucherTestData.receipt;
import static vn.ketoan.VoucherTestData.stores;

class VoucherServicesTest {

    @Test
    void creationServiceDelegatesToFactoryAndValidatorUsingTheSameProduct() {
        RecordingValidator validator = new RecordingValidator();
        VoucherCreationService service = new VoucherCreationService(new ReceiptCreator(), validator);

        Voucher voucher = service.createValidated(receipt());

        assertInstanceOf(ReceiptVoucher.class, voucher);
        assertSame(voucher, validator.lastVoucher);
        assertEquals(1, validator.calls);
    }

    @Test
    void creatingAnIncompleteDraftDoesNotCallSubmissionValidator() {
        RecordingValidator validator = new RecordingValidator();
        VoucherCreationService service = new VoucherCreationService(new ReceiptCreator(), validator);
        service.create(draft());

        assertEquals(0, validator.calls);
    }

    @Test
    void everyValidationReadsCurrentAccountPartnerFundAndPeriodData() {
        Stores stores = stores();
        VoucherValidationService validator = stores.validator();
        Voucher voucher = new ReceiptCreator().create(receipt());
        validator.validate(voucher);

        Account account = stores.accounts().findByCode("1111").orElseThrow();
        stores.accounts().update(account.deactivate());
        assertThrows(IllegalStateException.class, () -> validator.validate(voucher));
        stores.accounts().update(account);

        Partner partner = stores.partners().findByCode("KH01").orElseThrow();
        stores.partners().update(partner.deactivate());
        assertThrows(IllegalStateException.class, () -> validator.validate(voucher));
        stores.partners().update(partner);

        MoneyAccount fund = stores.moneyAccounts().findByCode("CASH01").orElseThrow();
        stores.moneyAccounts().update(fund.deactivate());
        assertThrows(IllegalStateException.class, () -> validator.validate(voucher));
        stores.moneyAccounts().update(fund);

        AccountingPeriod period = stores.periods().findByMonth(YearMonth.from(DATE)).orElseThrow();
        stores.periods().update(period.beginClosing());
        assertThrows(IllegalStateException.class, () -> validator.validate(voucher));
        stores.periods().update(period);
        validator.validate(voucher);
    }

    @Test
    void missingReferencesAreRejectedRatherThanCreatedAutomatically() {
        Stores stores = stores();
        VoucherValidationService validator = stores.validator();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> validator.validate(new ReceiptVoucher(draft(debit("1111", 10, null, "UNKNOWN"),
                                credit("131", 10, "KH01", null))))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> validator.validate(new ReceiptVoucher(draft(debit("1111", 10, null, "CASH01"),
                                credit("131", 10, "UNKNOWN", null))))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> validator.validate(new GeneralVoucher(draft(debit("999", 10, null, null),
                                credit("4111", 10, null, null))))));
        assertTrue(stores.moneyAccounts().findByCode("UNKNOWN").isEmpty());
        assertTrue(stores.partners().findByCode("UNKNOWN").isEmpty());
        assertTrue(stores.accounts().findByCode("999").isEmpty());
    }

    @Test
    void validationNeverOpensAMissingPeriod() {
        Stores stores = stores();
        InMemoryAccountingPeriodRepository periods = new InMemoryAccountingPeriodRepository();
        VoucherValidationService validator = new VoucherValidationService(stores.accounts(),
                stores.partners(), stores.moneyAccounts(), periods);

        assertThrows(IllegalArgumentException.class, () -> validator.validate(new ReceiptVoucher(receipt())));
        assertTrue(periods.findAll().isEmpty());
    }

    private static final class RecordingValidator implements VoucherValidator {
        private int calls;
        private Voucher lastVoucher;

        @Override
        public void validate(Voucher voucher) {
            calls++;
            lastVoucher = voucher;
        }
    }
}
