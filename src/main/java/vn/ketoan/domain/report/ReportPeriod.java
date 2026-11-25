package vn.ketoan.domain.report;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Objects;

/** Inclusive business posting dates; audit timestamps do not change the reporting period. */
public record ReportPeriod(LocalDate from, LocalDate to) {
    public ReportPeriod {
        requireDate(from);
        requireDate(to);
        if (from.isAfter(to)) { throw new IllegalArgumentException("Report start must not follow its end"); }
    }
    public static ReportPeriod month(YearMonth month) {
        Objects.requireNonNull(month, "Report month is required");
        return new ReportPeriod(month.atDay(1), month.atEndOfMonth());
    }
    public boolean contains(LocalDate date) { return !date.isBefore(from) && !date.isAfter(to); }
    public static void requireDate(LocalDate date) {
        Objects.requireNonNull(date, "Report date is required");
        if (date.getYear() < 1000 || date.getYear() > 9999) { throw new IllegalArgumentException("Report dates must be within years 1000 to 9999"); }
    }
}
