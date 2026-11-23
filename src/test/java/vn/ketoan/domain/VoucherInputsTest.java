package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.VoucherTestData.revenue;

class VoucherInputsTest {

    @Test
    void aLineHasOnePositiveSideAndCanonicalDetailCodes() {
        VoucherLine line = new VoucherLine(" tm ", BalanceSide.DEBIT, Money.of(4_000_000), " kh01 ", " cash01 ");

        assertAll(
                () -> assertEquals("TM", line.getAccountCode()),
                () -> assertEquals("KH01", line.getPartnerCode().orElseThrow()),
                () -> assertEquals("CASH01", line.getMoneyAccountCode().orElseThrow()),
                () -> assertEquals(Money.of(4_000_000), line.getDebit()),
                () -> assertEquals(Money.ZERO, line.getCredit()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, -4_000_000})
    void rejectsZeroAndNegativeLineAmounts(long amount) {
        assertThrows(IllegalArgumentException.class, () -> debit("131", amount, "KH01", null));
    }

    @Test
    void enforcesLineStoragePrecisionWithoutLimitingReportTotals() {
        Money maximum = Money.of("9999999999999999999");
        VoucherLine.credit("5113", maximum, null, null);
        assertThrows(IllegalArgumentException.class,
                () -> VoucherLine.credit("5113", maximum.add(Money.of(1)), null, null));
    }

    @Test
    void incompleteDraftIsAllowedAndLineListsCannotBeMutated() {
        List<VoucherLine> supplied = new ArrayList<>();
        Draft original = new Draft(" draft01 ", DATE, DATE, " Draft description ", supplied);
        supplied.add(debit("131", 10, "KH01", null));
        Draft completed = original.withLines(revenue().getLines());

        assertAll(
                () -> assertEquals("DRAFT01", original.getReference()),
                () -> assertEquals("Draft description", original.getDescription()),
                () -> assertTrue(original.getLines().isEmpty()),
                () -> assertEquals(2, completed.getLines().size()),
                () -> assertThrows(UnsupportedOperationException.class, completed.getLines()::clear));
    }

    @Test
    void documentDateAndPostingDateAreIndependent() {
        Draft input = new Draft("DATE01", LocalDate.of(2026, 12, 2), DATE, "Accrued event", revenue().getLines());
        assertEquals(DATE, input.getPostingDate());
        assertEquals(LocalDate.of(2026, 12, 2), input.getDocumentDate());
    }

    @Test
    void rejectsMissingValuesAndDatesOutsideStorageRange() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> new VoucherLine("131", null, Money.of(1), "KH01", null)),
                () -> assertThrows(NullPointerException.class,
                        () -> new VoucherLine("131", BalanceSide.DEBIT, null, "KH01", null)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Draft("TEST", DATE, DATE, " ", List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Draft("TEST", LocalDate.of(999, 1, 1), DATE, "Description", List.of())),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Draft("TEST", DATE, LocalDate.of(10000, 1, 1), "Description", List.of())),
                () -> assertThrows(NullPointerException.class,
                        () -> new Draft("TEST", DATE, DATE, "Description", null)));
    }
}
