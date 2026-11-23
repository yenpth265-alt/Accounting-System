package vn.ketoan.application.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.PeriodStatus;
import vn.ketoan.infrastructure.memory.InMemoryAccountingPeriodRepository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountingPeriodServiceTest {

    private InMemoryAccountingPeriodRepository repository;
    private AccountingPeriodService periods;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAccountingPeriodRepository();
        periods = new AccountingPeriodService(repository);
    }

    @Test
    void selectsPeriodByPostingDateIncludingBothMonthBoundaries() {
        periods.openPeriod(YearMonth.of(2026, 11));

        assertAll(
                () -> assertEquals(PeriodStatus.OPEN,
                        periods.requireOpenPeriod(LocalDate.of(2026, 11, 1)).getStatus()),
                () -> assertEquals(YearMonth.of(2026, 11),
                        periods.requireOpenPeriod(LocalDate.of(2026, 11, 30)).getMonth()));
    }

    @Test
    void duplicatePeriodCannotOverwriteExistingState() {
        AccountingPeriod closed = AccountingPeriod.open(YearMonth.of(2026, 11))
                .beginClosing().completeClosing();
        repository.insert(closed);

        assertThrows(IllegalStateException.class, () -> periods.openPeriod(YearMonth.of(2026, 11)));
        assertEquals(PeriodStatus.CLOSED, periods.get(YearMonth.of(2026, 11)).getStatus());
    }

    @ParameterizedTest
    @EnumSource(value = PeriodStatus.class, names = {"CLOSING", "CLOSED"})
    void rejectsOrdinaryPostingsWhenPeriodIsNotOpen(PeriodStatus status) {
        AccountingPeriod period = AccountingPeriod.open(YearMonth.of(2026, 11)).beginClosing();
        if (status == PeriodStatus.CLOSED) {
            period = period.completeClosing();
        }
        repository.insert(period);

        assertThrows(IllegalStateException.class,
                () -> periods.requireOpenPeriod(LocalDate.of(2026, 11, 23)));
    }

    @Test
    void missingPeriodIsNotAutomaticallyOpenedByAPostingRequest() {
        assertThrows(IllegalArgumentException.class,
                () -> periods.requireOpenPeriod(LocalDate.of(2026, 11, 23)));
        assertTrue(periods.list().isEmpty());
    }

    @Test
    void listsImmutablePeriodSnapshotsInChronologicalOrder() {
        periods.openPeriod(YearMonth.of(2026, 12));
        periods.openPeriod(YearMonth.of(2026, 11));
        List<AccountingPeriod> snapshot = periods.list();
        repository.update(periods.get(YearMonth.of(2026, 11)).beginClosing());

        assertAll(
                () -> assertEquals(List.of(YearMonth.of(2026, 11), YearMonth.of(2026, 12)),
                        snapshot.stream().map(AccountingPeriod::getMonth).toList()),
                () -> assertEquals(PeriodStatus.OPEN, snapshot.getFirst().getStatus()),
                () -> assertEquals(PeriodStatus.CLOSING,
                        periods.get(YearMonth.of(2026, 11)).getStatus()),
                () -> assertThrows(UnsupportedOperationException.class, snapshot::clear));
    }

    @Test
    void rejectsMissingPeriodKeysAndDates() {
        assertAll(
                () -> assertThrows(NullPointerException.class, () -> periods.openPeriod(null)),
                () -> assertThrows(NullPointerException.class, () -> periods.get(null)),
                () -> assertThrows(NullPointerException.class, () -> periods.requireOpenPeriod(null)));
    }
}
