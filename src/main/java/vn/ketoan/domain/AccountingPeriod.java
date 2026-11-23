package vn.ketoan.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** An immutable monthly accounting period. Closing entries belong to a separate use case. */
public final class AccountingPeriod {

    private final YearMonth month;
    private final PeriodStatus status;

    private AccountingPeriod(YearMonth month, PeriodStatus status) {
        this.month = Objects.requireNonNull(month, "Period month is required");
        this.status = Objects.requireNonNull(status, "Period status is required");
        if (month.getYear() < 1000 || month.getYear() > 9999) {
            throw new IllegalArgumentException("Period year must be between 1000 and 9999");
        }
    }

    public static AccountingPeriod open(YearMonth month) {
        return new AccountingPeriod(month, PeriodStatus.OPEN);
    }

    public AccountingPeriod beginClosing() {
        requireStatus(PeriodStatus.OPEN);
        return new AccountingPeriod(month, PeriodStatus.CLOSING);
    }

    public AccountingPeriod cancelClosing() {
        requireStatus(PeriodStatus.CLOSING);
        return new AccountingPeriod(month, PeriodStatus.OPEN);
    }

    public AccountingPeriod completeClosing() {
        requireStatus(PeriodStatus.CLOSING);
        return new AccountingPeriod(month, PeriodStatus.CLOSED);
    }

    public AccountingPeriod reopen() {
        requireStatus(PeriodStatus.CLOSED);
        return new AccountingPeriod(month, PeriodStatus.OPEN);
    }

    private void requireStatus(PeriodStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "Period " + month + " must be " + expected + " but is " + status);
        }
    }

    public boolean contains(LocalDate postingDate) {
        Objects.requireNonNull(postingDate, "Posting date is required");
        return month.equals(YearMonth.from(postingDate));
    }

    public void requireOpenForPosting(LocalDate postingDate) {
        if (!contains(postingDate)) {
            throw new IllegalArgumentException(
                    "Posting date " + postingDate + " is outside period " + month);
        }
        requireStatus(PeriodStatus.OPEN);
    }

    public YearMonth getMonth() {
        return month;
    }

    public LocalDate getStartDate() {
        return month.atDay(1);
    }

    public LocalDate getEndDate() {
        return month.atEndOfMonth();
    }

    public PeriodStatus getStatus() {
        return status;
    }
}
