package vn.ketoan.domain;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.accounts;
import static vn.ketoan.VoucherTestData.context;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.VoucherTestData.draft;
import static vn.ketoan.VoucherTestData.moneyAccounts;
import static vn.ketoan.VoucherTestData.partners;
import static vn.ketoan.VoucherTestData.receipt;
import static vn.ketoan.VoucherTestData.revenue;

class VoucherTemplateTest {

    @Test
    void invokesSubtypeHookOnlyAfterCommonChecksPass() {
        ProbeVoucher valid = new ProbeVoucher(receipt());
        valid.validateForSubmission(context());
        assertEquals(1, valid.hookCalls);

        ProbeVoucher unbalanced = new ProbeVoucher(draft(
                debit("1111", 4_000_000, null, "CASH01"), credit("131", 3_000_000, "KH01", null)));
        assertThrows(IllegalArgumentException.class, () -> unbalanced.validateForSubmission(context()));
        assertEquals(0, unbalanced.hookCalls);
    }

    @Test
    void closedPeriodCannotReachSubtypeHook() {
        ProbeVoucher voucher = new ProbeVoucher(receipt());
        VoucherValidationContext closed = new VoucherValidationContext(accounts(), partners(), moneyAccounts(),
                AccountingPeriod.open(YearMonth.from(DATE)).beginClosing().completeClosing());

        assertThrows(IllegalStateException.class, () -> voucher.validateForSubmission(closed));
        assertEquals(0, voucher.hookCalls);
    }

    @Test
    void rejectsEmptyAndSingleLineDrafts() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProbeVoucher(draft()).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProbeVoucher(draft(debit("131", 10, "KH01", null)))
                                .validateForSubmission(context())));
    }

    @Test
    void rejectsGroupAccountBeforeSubtypeHook() {
        ProbeVoucher voucher = new ProbeVoucher(draft(
                debit("111", 10, null, null), credit("4111", 10, null, null)));
        assertThrows(IllegalStateException.class, () -> voucher.validateForSubmission(context()));
        assertEquals(0, voucher.hookCalls);
    }

    @Test
    void requiresPartnerAndFundDetails() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProbeVoucher(draft(debit("1111", 10, null, "CASH01"),
                                credit("131", 10, null, null))).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProbeVoucher(draft(debit("1111", 10, null, null),
                                credit("131", 10, "KH01", null))).validateForSubmission(context())));
    }

    @Test
    void rejectsFundBoundToAnotherLedgerAccountOrAddedToNonMoneyAccount() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProbeVoucher(draft(debit("1111", 10, null, "BANK01"),
                                credit("131", 10, "KH01", null))).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ProbeVoucher(draft(debit("131", 10, "KH01", "CASH01"),
                                credit("5113", 10, null, null))).validateForSubmission(context())));
    }

    @Test
    void rejectsInactiveMasterData() {
        Voucher voucher = new ReceiptVoucher(receipt());
        VoucherValidationContext inactiveAccount = new VoucherValidationContext(
                accounts().stream().map(a -> a.getCode().equals("1111") ? a.deactivate() : a).toList(),
                partners(), moneyAccounts(), AccountingPeriod.open(YearMonth.from(DATE)));
        VoucherValidationContext inactivePartner = new VoucherValidationContext(accounts(),
                partners().stream().map(p -> p.getCode().equals("KH01") ? p.deactivate() : p).toList(),
                moneyAccounts(), AccountingPeriod.open(YearMonth.from(DATE)));
        VoucherValidationContext inactiveFund = new VoucherValidationContext(accounts(), partners(),
                moneyAccounts().stream().map(a -> a.getCode().equals("CASH01") ? a.deactivate() : a).toList(),
                AccountingPeriod.open(YearMonth.from(DATE)));

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> voucher.validateForSubmission(inactiveAccount)),
                () -> assertThrows(IllegalStateException.class, () -> voucher.validateForSubmission(inactivePartner)),
                () -> assertThrows(IllegalStateException.class, () -> voucher.validateForSubmission(inactiveFund)));
    }

    @Test
    void contextDoesNotRetainMutableCollectionsAndReportsMissingReferences() {
        ArrayList<Account> supplied = new ArrayList<>(accounts());
        VoucherValidationContext snapshot = new VoucherValidationContext(supplied, partners(), moneyAccounts(),
                AccountingPeriod.open(YearMonth.from(DATE)));
        supplied.clear();

        new ReceiptVoucher(receipt()).validateForSubmission(snapshot);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> snapshot.requireAccount("999")),
                () -> assertThrows(IllegalArgumentException.class, () -> snapshot.requirePartner("UNKNOWN")),
                () -> assertThrows(IllegalArgumentException.class, () -> snapshot.requireMoneyAccount("UNKNOWN")));
    }

    @Test
    void totalsAndContentRemainUnchangedAfterValidation() {
        Voucher voucher = new RevenueVoucher(revenue());
        List<VoucherLine> lines = voucher.getLines();
        voucher.validateForSubmission(context());

        assertEquals(Money.of(10_000_000), voucher.getTotalDebit());
        assertEquals(Money.of(10_000_000), voucher.getTotalCredit());
        assertThrows(UnsupportedOperationException.class, lines::clear);
    }

    private static final class ProbeVoucher extends Voucher {
        private int hookCalls;

        private ProbeVoucher(Draft input) {
            super(VoucherType.GENERAL, input);
        }

        @Override
        protected void validateSpecific(VoucherValidationContext context) {
            hookCalls++;
        }
    }
}
