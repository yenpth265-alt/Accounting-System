package vn.ketoan.application.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;

import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.domain.AccountingPeriod;

public final class AccountingPeriodService {

    private final AccountingPeriodRepository periods;

    public AccountingPeriodService(AccountingPeriodRepository periods) {
        this.periods = Objects.requireNonNull(periods, "Period repository is required");
    }

    public void openPeriod(YearMonth month) {
        periods.insert(AccountingPeriod.open(month));
    }

    public AccountingPeriod get(YearMonth month) {
        Objects.requireNonNull(month, "Period month is required");
        return periods.findByMonth(month).orElseThrow(
                () -> new IllegalArgumentException("Accounting period not found: " + month));
    }

    public List<AccountingPeriod> list() {
        return periods.findAll();
    }

    public AccountingPeriod requireOpenPeriod(LocalDate postingDate) {
        Objects.requireNonNull(postingDate, "Posting date is required");
        AccountingPeriod period = get(YearMonth.from(postingDate));
        period.requireOpenForPosting(postingDate);
        return period;
    }
}
