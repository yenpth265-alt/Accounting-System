package vn.ketoan.domain.value;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MoneyTest {

    @Test
    void calculatesRemainingReceivableAfterPartialPayment() {
        Money receivable = Money.of(10_000_000);
        Money payment = Money.of(4_000_000);

        assertEquals(Money.of(6_000_000), receivable.subtract(payment));
    }

    @Test
    void addsLargeAmountsWithoutFloatingPointPrecisionLoss() {
        Money amount = Money.of("9007199254740993");

        assertEquals(Money.of("9007199254740994"), amount.add(Money.of(1)));
    }

    @Test
    void supportsNegativeBalancesWithoutChangingTheirMeaning() {
        Money balance = Money.of(1_000_000).subtract(Money.of(2_000_000));

        assertAll(
                () -> assertEquals(Money.of(-1_000_000), balance),
                () -> assertTrue(balance.isNegative()),
                () -> assertEquals(Money.of(1_000_000), balance.negate()));
    }

    @Test
    void calculationsDoNotChangeOriginalAmounts() {
        Money openingBalance = Money.of(100_000_000);
        Money endingBalance = openingBalance.subtract(Money.of(1_000_000));

        assertAll(
                () -> assertEquals(Money.of(100_000_000), openingBalance),
                () -> assertEquals(Money.of(99_000_000), endingBalance));
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "100.0", "100.00", "1E+2"})
    void treatsEquivalentWholeDongAmountsAsTheSameValue(String input) {
        Money amount = Money.of(input);
        Money expected = Money.of(100);
        Set<Money> amounts = new HashSet<>();
        amounts.add(amount);
        amounts.add(expected);

        assertAll(
                () -> assertEquals(expected, amount),
                () -> assertEquals(0, amount.compareTo(expected)),
                () -> assertEquals(1, amounts.size()),
                () -> assertEquals("100", amount.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1", "1.01", "-0.1", "100.0001"})
    void rejectsFractionsOfADongInsteadOfSilentlyRounding(String input) {
        assertThrows(IllegalArgumentException.class, () -> Money.of(input));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "abc", "NaN", "Infinity"})
    void rejectsNonNumericAmounts(String input) {
        assertThrows(IllegalArgumentException.class, () -> Money.of(input));
    }

    @Test
    void preservesExactValuesWhenReadingDecimalAmounts() {
        Money amount = Money.of(new BigDecimal("1200000.000"));

        assertEquals(new BigDecimal("1200000"), amount.toBigDecimal());
    }

    @Test
    void comparesAmountsNumerically() {
        assertAll(
                () -> assertTrue(Money.of(9).compareTo(Money.of(10)) < 0),
                () -> assertTrue(Money.of(100).compareTo(Money.of(9)) > 0),
                () -> assertTrue(Money.of(-1).compareTo(Money.ZERO) < 0));
    }

    @Test
    void fullSettlementProducesZeroBalance() {
        Money balance = Money.of(10_000_000).subtract(Money.of(10_000_000));

        assertAll(
                () -> assertEquals(Money.ZERO, balance),
                () -> assertTrue(balance.isZero()),
                () -> assertFalse(balance.isPositive()),
                () -> assertFalse(balance.isNegative()));
    }

    @Test
    void rejectsMissingAmountsAndOperands() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> Money.of((String) null)),
                () -> assertThrows(NullPointerException.class,
                        () -> Money.of((BigDecimal) null)),
                () -> assertThrows(NullPointerException.class,
                        () -> Money.ZERO.add(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> Money.ZERO.subtract(null)),
                () -> assertThrows(NullPointerException.class,
                        () -> Money.ZERO.compareTo(null)));
    }
}
