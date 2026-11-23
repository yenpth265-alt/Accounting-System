package vn.ketoan.domain;

import java.time.LocalDate;
import java.time.YearMonth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountingPeriodTest {

    @ParameterizedTest
    @CsvSource({"2026-11,2026-11-30", "2026-12,2026-12-31", "2028-02,2028-02-29",
            "2026-02,2026-02-28", "2000-02,2000-02-29", "1900-02,1900-02-28"})
    void derivesActualMonthBoundariesIncludingLeapYears(String month, String expectedEnd) {
        AccountingPeriod period = AccountingPeriod.open(YearMonth.parse(month));

        assertAll(
                () -> assertEquals(YearMonth.parse(month).atDay(1), period.getStartDate()),
                () -> assertEquals(LocalDate.parse(expectedEnd), period.getEndDate()));
    }

    @Test
    void includesBothBoundariesAndExcludesAdjacentMonths() {
        AccountingPeriod period = november();

        assertAll(
                () -> assertTrue(period.contains(LocalDate.of(2026, 11, 1))),
                () -> assertTrue(period.contains(LocalDate.of(2026, 11, 30))),
                () -> assertFalse(period.contains(LocalDate.of(2026, 10, 31))),
                () -> assertFalse(period.contains(LocalDate.of(2026, 12, 1))));
    }

    @Test
    void closingTransitionsDoNotMutatePreviousPeriodSnapshots() {
        AccountingPeriod open = november();
        AccountingPeriod closing = open.beginClosing();
        AccountingPeriod closed = closing.completeClosing();

        assertAll(
                () -> assertEquals(PeriodStatus.OPEN, open.getStatus()),
                () -> assertEquals(PeriodStatus.CLOSING, closing.getStatus()),
                () -> assertEquals(PeriodStatus.CLOSED, closed.getStatus()),
                () -> assertEquals(PeriodStatus.OPEN, closing.cancelClosing().getStatus()),
                () -> assertEquals(PeriodStatus.OPEN, closed.reopen().getStatus()));
    }

    @Test
    void cannotSkipOrRepeatClosingTransitions() {
        AccountingPeriod open = november();
        AccountingPeriod closing = open.beginClosing();
        AccountingPeriod closed = closing.completeClosing();

        assertAll(
                () -> assertThrows(IllegalStateException.class, open::completeClosing),
                () -> assertThrows(IllegalStateException.class, open::cancelClosing),
                () -> assertThrows(IllegalStateException.class, open::reopen),
                () -> assertThrows(IllegalStateException.class, closing::beginClosing),
                () -> assertThrows(IllegalStateException.class, closing::reopen),
                () -> assertThrows(IllegalStateException.class, closed::beginClosing),
                () -> assertThrows(IllegalStateException.class, closed::completeClosing),
                () -> assertThrows(IllegalStateException.class, closed::cancelClosing));
    }

    @Test
    void ordinaryPostingRequiresMatchingDateAndOpenState() {
        AccountingPeriod open = november();
        LocalDate date = LocalDate.of(2026, 11, 23);

        open.requireOpenForPosting(date);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> open.requireOpenForPosting(LocalDate.of(2026, 12, 1))),
                () -> assertThrows(IllegalStateException.class,
                        () -> open.beginClosing().requireOpenForPosting(date)),
                () -> assertThrows(IllegalStateException.class,
                        () -> open.beginClosing().completeClosing().requireOpenForPosting(date)));
    }

    @ParameterizedTest
    @ValueSource(ints = {999, 10000})
    void rejectsYearsOutsideTheDatabaseDateRange(int year) {
        assertThrows(IllegalArgumentException.class,
                () -> AccountingPeriod.open(YearMonth.of(year, 1)));
    }

    @Test
    void rejectsMissingMonthOrPostingDate() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> AccountingPeriod.open(null)),
                () -> assertThrows(NullPointerException.class, () -> november().contains(null)));
    }

    private AccountingPeriod november() {
        return AccountingPeriod.open(YearMonth.of(2026, 11));
    }
}
