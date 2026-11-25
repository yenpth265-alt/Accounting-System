package vn.ketoan.domain;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.context;

class RecognitionDraftTest {

    @Test
    void normalizesInputAndDefensivelyCopiesItsItems() {
        List<RecognitionItem> items = new ArrayList<>();
        items.add(new RecognitionItem(" 5113 ", Money.of(10)));
        RecognitionDraft input = new RecognitionDraft(" dt-01 ", DATE.minusDays(1), DATE,
                " Completed service ", " kh01 ", " 131 ", items);
        items.clear();

        assertEquals("DT-01", input.getReference());
        assertEquals("KH01", input.getPartnerCode());
        assertEquals("131", input.getDebtAccountCode());
        assertEquals("Completed service", input.getDescription());
        assertEquals(DATE.minusDays(1), input.getDocumentDate());
        assertEquals(DATE, input.getPostingDate());
        assertEquals("5113", input.getItems().getFirst().getAccountCode());
        assertEquals(Money.of(10), input.getTotal());
        assertThrows(UnsupportedOperationException.class, input.getItems()::clear);
    }

    @Test
    void generatesReceivableIncludingSeparatelyEnteredTax() {
        RecognitionDraft input = input("KH01", "131",
                new RecognitionItem("5113", Money.of(10_000_000)),
                new RecognitionItem("33311", Money.of(1_000_000)));
        Voucher voucher = new RevenueCreator().create(input.toRevenueDraft());
        voucher.validateForSubmission(context());

        assertEquals(Money.of(11_000_000), voucher.getTotalDebit());
        assertEquals(voucher.getTotalDebit(), voucher.getTotalCredit());
        VoucherLine debt = voucher.getLines().getFirst();
        assertEquals("131", debt.getAccountCode());
        assertEquals("KH01", debt.getPartnerCode().orElseThrow());
        assertEquals(BalanceSide.DEBIT, debt.getSide());
        assertEquals(Money.of(10_000_000), voucher.getLines().get(1).getCredit());
        assertEquals(Money.of(1_000_000), voucher.getLines().getLast().getCredit());
        assertTrue(voucher.getLines().stream().allMatch(line -> line.getMoneyAccountCode().isEmpty()));
        assertTrue(voucher.getLines().subList(1, 3).stream().allMatch(line -> line.getPartnerCode().isEmpty()));
    }

    @Test
    void generatesPayableIncludingSeparatelyEnteredRecoverableTax() {
        RecognitionDraft input = input("NCC01", "331",
                new RecognitionItem("6427", Money.of(3_000_000)),
                new RecognitionItem("1331", Money.of(300_000)));
        Voucher voucher = new ExpenseCreator().create(input.toExpenseDraft());
        voucher.validateForSubmission(context());

        assertEquals(Money.of(3_300_000), voucher.getTotalCredit());
        assertEquals(voucher.getTotalDebit(), voucher.getTotalCredit());
        VoucherLine debt = voucher.getLines().getLast();
        assertEquals("331", debt.getAccountCode());
        assertEquals("NCC01", debt.getPartnerCode().orElseThrow());
        assertEquals(BalanceSide.CREDIT, debt.getSide());
        assertEquals(Money.of(3_000_000), voucher.getLines().getFirst().getDebit());
        assertEquals(Money.of(300_000), voucher.getLines().get(1).getDebit());
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10000000000000000000"})
    void rejectsNonPositiveOrOversizedComponentAmounts(String amount) {
        assertThrows(IllegalArgumentException.class, () -> new RecognitionItem("5113", Money.of(amount)));
    }

    @Test
    void rejectsEmptyItemsAndAnOverflowingDebtTotal() {
        assertThrows(IllegalArgumentException.class, () -> input("KH01", "131"));
        assertThrows(IllegalArgumentException.class, () -> input("KH01", "131",
                new RecognitionItem("5113", Money.of("9999999999999999999")),
                new RecognitionItem("33311", Money.of(1))));
    }

    private RecognitionDraft input(String partner, String debtAccount, RecognitionItem... items) {
        return new RecognitionDraft("TEST", DATE, DATE, "Recognized service", partner, debtAccount, List.of(items));
    }
}
