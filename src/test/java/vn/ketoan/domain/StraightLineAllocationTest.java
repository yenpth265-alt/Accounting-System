package vn.ketoan.domain;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StraightLineAllocationTest {

    @Test
    void retainsEveryDongAndAssignsRemainderToTheLastMonth() {
        ExpenseAllocationStrategy strategy = new StraightLineAllocation();
        assertEquals(List.of(Money.of(33), Money.of(33), Money.of(34)), strategy.allocate(Money.of(100), 3));
        assertEquals(List.of(Money.of(100)), strategy.allocate(Money.of(100), 1));
        Money maximum = Money.of("9999999999999999999");
        assertEquals(maximum, strategy.allocate(maximum, 12).stream().reduce(Money.ZERO, Money::add));
        assertThrows(UnsupportedOperationException.class, strategy.allocate(Money.of(100), 3)::clear);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, 121})
    void rejectsInvalidMonthCounts(int months) {
        assertThrows(IllegalArgumentException.class, () -> new StraightLineAllocation().allocate(Money.of(100), months));
    }

    @Test
    void eachGeneratedPostingMustHaveAtLeastOneWholeDong() {
        assertThrows(IllegalArgumentException.class, () -> new StraightLineAllocation().allocate(Money.of(2), 3));
        assertThrows(IllegalArgumentException.class, () -> new StraightLineAllocation().allocate(Money.of(-1), 1));
    }
}
