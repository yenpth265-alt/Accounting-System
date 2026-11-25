package vn.ketoan.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vn.ketoan.VoucherTestData.DATE;

class SettlementInputsTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "10000000000000000000"})
    void settlementAndAllocationAmountsMustBePositiveAndFitStorage(String amount) {
        Money money = Money.of(amount);
        assertThrows(IllegalArgumentException.class, () -> new SettlementDraft(
                "PT001", DATE, DATE, "Customer payment", "KH01", "131", "CASH01", money));
        assertThrows(IllegalArgumentException.class, () -> new AllocationRequest(UUID.randomUUID(),
                new JournalLineKey(UUID.randomUUID(), 1), new JournalLineKey(UUID.randomUUID(), 2), money, DATE, "Match"));
    }

    @Test
    void allocationRequiresDifferentValidLinesAndANote() {
        JournalLineKey line = new JournalLineKey(UUID.randomUUID(), 1);
        assertThrows(IllegalArgumentException.class,
                () -> new JournalLineKey(UUID.randomUUID(), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new AllocationRequest(UUID.randomUUID(), line, line, Money.of(1), DATE, "Same line"));
        assertThrows(IllegalArgumentException.class, () -> new AllocationRequest(UUID.randomUUID(), line,
                new JournalLineKey(UUID.randomUUID(), 1), Money.of(1), DATE, " "));
    }

    @Test
    void settlementNormalizesCodesAndGeneratesOppositeSidesWithOnlyOneCounterparty() {
        SettlementDraft input = new SettlementDraft(" pt001 ", DATE, DATE, " Receipt ",
                " kh01 ", " 131 ", " cash01 ", Money.of(4));
        Draft draft = input.toReceiptDraft("1111");
        assertEquals("PT001", draft.getReference());
        assertEquals("KH01", input.getPartnerCode());
        assertEquals("131", input.getDebtAccountCode());
        assertEquals("CASH01", input.getMoneyAccountCode());
        assertEquals(Money.of(4), input.getAmount());
        assertEquals(Money.of(4), draft.getLines().getFirst().getDebit());
        assertEquals(Money.of(4), draft.getLines().getLast().getCredit());
        assertEquals("KH01", draft.getLines().getLast().getPartnerCode().orElseThrow());
    }
}
