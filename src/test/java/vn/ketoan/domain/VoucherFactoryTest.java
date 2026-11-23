package vn.ketoan.domain;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.VoucherTestData.context;
import static vn.ketoan.VoucherTestData.draft;
import static vn.ketoan.VoucherTestData.expense;
import static vn.ketoan.VoucherTestData.general;
import static vn.ketoan.VoucherTestData.payment;
import static vn.ketoan.VoucherTestData.receipt;
import static vn.ketoan.VoucherTestData.revenue;

class VoucherFactoryTest {

    static Stream<Arguments> products() {
        return Stream.of(
                Arguments.of(new RevenueCreator(), RevenueVoucher.class, VoucherType.REVENUE, revenue()),
                Arguments.of(new ExpenseCreator(), ExpenseVoucher.class, VoucherType.EXPENSE, expense()),
                Arguments.of(new ReceiptCreator(), ReceiptVoucher.class, VoucherType.RECEIPT, receipt()),
                Arguments.of(new PaymentCreator(), PaymentVoucher.class, VoucherType.PAYMENT, payment()),
                Arguments.of(new GeneralCreator(), GeneralVoucher.class, VoucherType.GENERAL, general()));
    }

    @ParameterizedTest
    @MethodSource("products")
    void factoryMethodCreatesTheRightProductAndEveryProductWorksThroughBaseType(
            VoucherCreator creator, Class<? extends Voucher> productClass, VoucherType type, Draft input) {
        Voucher voucher = creator.create(input);

        assertInstanceOf(productClass, voucher);
        assertEquals(type, voucher.getType());
        assertEquals(input.getReference(), voucher.getReference());
        assertEquals(input.getDocumentDate(), voucher.getDocumentDate());
        assertEquals(input.getPostingDate(), voucher.getPostingDate());
        assertEquals(input.getDescription(), voucher.getDescription());
        assertEquals(voucher.getTotalDebit(), voucher.getTotalCredit());
        voucher.validateForSubmission(context());
    }

    @Test
    void creatorDelegatesInstantiationToTheOverriddenFactoryMethod() {
        CountingCreator creator = new CountingCreator();

        Voucher voucher = creator.create(receipt());

        assertEquals(1, creator.calls);
        assertInstanceOf(ReceiptVoucher.class, voucher);
        assertThrows(NullPointerException.class, () -> creator.create(null));
        assertEquals(1, creator.calls);
    }

    @Test
    void creationDoesNotForceAnIncompleteDraftToBeSubmitted() {
        Voucher voucher = new ReceiptCreator().create(draft());

        assertTrue(voucher.getLines().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> voucher.validateForSubmission(context()));
    }

    @Test
    void aBrokenCreatorCannotReturnNull() {
        VoucherCreator creator = new VoucherCreator() {
            @Override
            protected Voucher createVoucher(Draft input) {
                return null;
            }
        };
        assertThrows(NullPointerException.class, () -> creator.create(receipt()));
    }

    private static final class CountingCreator extends VoucherCreator {
        private int calls;

        @Override
        protected Voucher createVoucher(Draft input) {
            calls++;
            return new ReceiptVoucher(input);
        }
    }
}
