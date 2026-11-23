package vn.ketoan.infrastructure.memory;

import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.domain.AccountingPeriod;

/** Single-process demo storage. Cross-entity transactions are not provided here. */
public final class InMemoryAccountingPeriodRepository implements AccountingPeriodRepository {

    private final Map<YearMonth, AccountingPeriod> periods = new TreeMap<>();

    @Override
    public Optional<AccountingPeriod> findByMonth(YearMonth month) {
        return Optional.ofNullable(periods.get(Objects.requireNonNull(month, "Period month is required")));
    }

    @Override
    public List<AccountingPeriod> findAll() {
        return List.copyOf(periods.values());
    }

    @Override
    public void insert(AccountingPeriod period) {
        Objects.requireNonNull(period, "Accounting period is required");
        if (periods.putIfAbsent(period.getMonth(), period) != null) {
            throw new IllegalStateException("Accounting period already exists: " + period.getMonth());
        }
    }

    @Override
    public void update(AccountingPeriod period) {
        Objects.requireNonNull(period, "Accounting period is required");
        if (periods.replace(period.getMonth(), period) == null) {
            throw new IllegalArgumentException("Accounting period not found: " + period.getMonth());
        }
    }
}
