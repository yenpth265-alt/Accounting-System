package vn.ketoan.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vn.ketoan.VoucherTestData.context;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.VoucherTestData.draft;
import static vn.ketoan.VoucherTestData.expense;
import static vn.ketoan.VoucherTestData.general;
import static vn.ketoan.VoucherTestData.payment;
import static vn.ketoan.VoucherTestData.receipt;

class VoucherRulesTest {

    @Test
    void revenueSupportsSeparateTaxLiabilityWithoutAssumingATaxRate() {
        new RevenueVoucher(draft(debit("131", 11_000_000, "KH01", null),
                credit("5113", 10_000_000, null, null), credit("33311", 1_000_000, null, null)))
                .validateForSubmission(context());
    }

    @Test
    void revenueCannotBeReplacedByOnlyATaxOrOtherLiabilityCredit() {
        assertThrows(IllegalArgumentException.class,
                () -> new RevenueVoucher(draft(debit("131", 10, "KH01", null),
                        credit("33311", 10, null, null))).validateForSubmission(context()));
    }

    @Test
    void revenueRequiresCustomerReceivableAndOneCustomer() {
        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> new RevenueVoucher(draft(debit("131", 10, "NCC01", null),
                                credit("5113", 10, null, null))).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RevenueVoucher(draft(debit("1331", 10, null, null),
                                credit("5113", 10, null, null))).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RevenueVoucher(draft(debit("131", 4, "KH01", null),
                                debit("131", 6, "KH02", null), credit("5113", 10, null, null)))
                                .validateForSubmission(context())));
    }

    @Test
    void revenueAndExpenseRecognitionDoNotMoveMoney() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new RevenueVoucher(draft(debit("1111", 10, null, "CASH01"),
                                credit("5113", 10, null, null))).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ExpenseVoucher(draft(debit("6427", 10, null, null),
                                credit("1111", 10, null, "CASH01"))).validateForSubmission(context())));
    }

    @Test
    void expenseSupportsSeparateRecoverableTaxOrRelatedAssetLines() {
        new ExpenseVoucher(draft(debit("6427", 3_000_000, null, null),
                debit("1331", 300_000, null, null), credit("331", 3_300_000, "NCC01", null)))
                .validateForSubmission(context());
    }

    @Test
    void expenseRequiresExpenseDebitAndSupplierPayable() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ExpenseVoucher(draft(debit("1331", 10, null, null),
                                credit("331", 10, "NCC01", null))).validateForSubmission(context())),
                () -> assertThrows(IllegalStateException.class,
                        () -> new ExpenseVoucher(draft(debit("6427", 10, null, null),
                                credit("331", 10, "KH01", null))).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ExpenseVoucher(draft(debit("6427", 10, null, null),
                                credit("131", 10, "KH01", null))).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ExpenseVoucher(draft(debit("6427", 10, null, null),
                                credit("331", 4, "NCC01", null), credit("331", 6, "DT01", null)))
                                .validateForSubmission(context())));
    }

    @Test
    void receiptAndPaymentRejectTheOppositeMoneyDirection() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ReceiptVoucher(payment()).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PaymentVoucher(receipt()).validateForSubmission(context())));
    }

    @Test
    void receiptAndPaymentRequireAnActualMoneyLine() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ReceiptVoucher(expense()).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PaymentVoucher(expense()).validateForSubmission(context())));
    }

    @Test
    void settlementCannotRecognizeRevenueOrExpenseAgain() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ReceiptVoucher(draft(debit("1111", 10, null, "CASH01"),
                                credit("5113", 10, null, null))).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PaymentVoucher(draft(debit("6427", 10, null, null),
                                credit("1111", 10, null, "CASH01"))).validateForSubmission(context())));
    }

    @Test
    void settlementChecksTheCounterpartyRole() {
        assertAll(
                () -> assertThrows(IllegalStateException.class,
                        () -> new ReceiptVoucher(draft(debit("1111", 10, null, "CASH01"),
                                credit("131", 10, "NCC01", null))).validateForSubmission(context())),
                () -> assertThrows(IllegalStateException.class,
                        () -> new PaymentVoucher(draft(debit("331", 10, "KH01", null),
                                credit("1111", 10, null, "CASH01"))).validateForSubmission(context())));
    }

    @Test
    void settlementAllowsAdvancesRefundsAndCapitalWithoutForcingIncomeOrExpense() {
        new ReceiptVoucher(draft(debit("1111", 10, null, "CASH01"),
                credit("131", 10, "KH01", null))).validateForSubmission(context());
        new PaymentVoucher(draft(debit("331", 10, "NCC01", null),
                credit("1111", 10, null, "CASH01"))).validateForSubmission(context());
        new ReceiptVoucher(draft(debit("1111", 10, null, "CASH01"),
                credit("331", 10, "NCC01", null))).validateForSubmission(context());
        new PaymentVoucher(draft(debit("131", 10, "KH01", null),
                credit("1111", 10, null, "CASH01"))).validateForSubmission(context());
        new ReceiptVoucher(draft(debit("1111", 10, null, "CASH01"),
                credit("4111", 10, null, null))).validateForSubmission(context());
    }

    @Test
    void internalTransferIsGeneralRatherThanAnExternalReceiptOrPayment() {
        new GeneralVoucher(general()).validateForSubmission(context());
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new ReceiptVoucher(general()).validateForSubmission(context())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new PaymentVoucher(general()).validateForSubmission(context())));
    }

    @Test
    void generalEntryCannotJustCancelItselfOnTheSameAnalyticAccount() {
        assertThrows(IllegalArgumentException.class,
                () -> new GeneralVoucher(draft(debit("131", 10, "KH01", null),
                        credit("131", 4, "KH01", null), credit("131", 6, "KH01", null)))
                        .validateForSubmission(context()));
    }

    @Test
    void generalEntryMayMoveBetweenPartnersOrFundsOnTheSameLedgerAccount() {
        new GeneralVoucher(draft(debit("131", 10, "KH01", null),
                credit("131", 10, "KH02", null))).validateForSubmission(context());
        new GeneralVoucher(draft(debit("1111", 10, null, "CASH01"),
                credit("1111", 10, null, "CASH02"))).validateForSubmission(context());
    }
}
