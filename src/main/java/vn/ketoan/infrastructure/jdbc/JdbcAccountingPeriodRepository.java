package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.PeriodStatus;

final class JdbcAccountingPeriodRepository implements AccountingPeriodRepository {

    private final JdbcContext context;

    JdbcAccountingPeriodRepository(JdbcContext context) {
        this.context = context;
    }

    @Override
    public Optional<AccountingPeriod> findByMonth(YearMonth month) {
        return context.guard(() -> context.one("SELECT * FROM acc_periods WHERE month_key=?", this::read,
                Objects.requireNonNull(month, "Period month is required").toString()));
    }

    @Override
    public List<AccountingPeriod> findAll() {
        return context.query("SELECT * FROM acc_periods ORDER BY month_key", this::read);
    }

    @Override
    public void insert(AccountingPeriod value) {
        context.mutate(() -> context.update("INSERT INTO acc_periods(month_key,status) VALUES (?,?)", value.getMonth().toString(), value.getStatus()));
    }

    @Override
    public void update(AccountingPeriod value) {
        context.mutate(() -> {
            findByMonth(value.getMonth()).orElseThrow(() -> new IllegalArgumentException("Period not found: " + value.getMonth()));
            context.update("UPDATE acc_periods SET status=? WHERE month_key=?", value.getStatus(), value.getMonth().toString());
        });
    }

    private AccountingPeriod read(ResultSet row) throws SQLException {
        AccountingPeriod period = AccountingPeriod.open(YearMonth.parse(row.getString("month_key")));
        return switch (PeriodStatus.valueOf(row.getString("status"))) {
            case OPEN -> period;
            case CLOSING -> period.beginClosing();
            case CLOSED -> period.beginClosing().completeClosing();
        };
    }
}
