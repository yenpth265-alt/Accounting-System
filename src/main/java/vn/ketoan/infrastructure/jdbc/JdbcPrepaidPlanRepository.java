package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.PrepaidPlanRepository;
import vn.ketoan.domain.JournalLineKey;
import vn.ketoan.domain.PrepaidExpensePeriod;
import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidPlanCancellation;
import vn.ketoan.domain.PrepaidPlanRequest;
import vn.ketoan.domain.value.Money;

final class JdbcPrepaidPlanRepository implements PrepaidPlanRepository {

    private final JdbcContext context;

    JdbcPrepaidPlanRepository(JdbcContext context) { this.context = context; }

    @Override public Optional<PrepaidExpensePlan> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_prepaid_plans WHERE id=?", this::read,
                Objects.requireNonNull(id, "Prepaid plan id is required")));
    }
    @Override public List<PrepaidExpensePlan> findAll() {
        return context.query("SELECT * FROM acc_prepaid_plans ORDER BY reference", this::read);
    }
    @Override public void append(PrepaidExpensePlan value) {
        context.mutate(() -> {
            var request = value.request();
            context.update("""
                    INSERT INTO acc_prepaid_plans(id,reference,source_entry_id,source_line_no,expense_account_code,first_month,
                    month_count,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?)
                    """, value.id(), request.reference(), request.source().journalEntryId(), request.source().lineNumber(),
                    request.expenseAccountCode(), request.firstMonth().toString(), request.months(), value.createdBy(), value.createdAt());
            value.schedule().forEach(period -> context.update(
                    "INSERT INTO acc_prepaid_schedule(plan_id,month_key,amount) VALUES (?,?,?)",
                    value.id(), period.month().toString(), period.amount()));
        });
    }
    @Override public Optional<PrepaidPlanCancellation> findCancellation(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_prepaid_cancellations WHERE plan_id=?", this::readCancellation,
                Objects.requireNonNull(id, "Prepaid plan id is required")));
    }
    @Override public List<PrepaidPlanCancellation> findCancellations() {
        return context.query("SELECT * FROM acc_prepaid_cancellations ORDER BY effective_date,plan_id", this::readCancellation);
    }
    @Override public void appendCancellation(PrepaidPlanCancellation value) {
        context.mutate(() -> {
            findById(value.planId()).orElseThrow(() -> new IllegalArgumentException("Prepaid plan not found"));
            context.update("""
                    INSERT INTO acc_prepaid_cancellations(plan_id,effective_date,reason,recorded_by,recorded_at) VALUES (?,?,?,?,?)
                    """, value.planId(), value.effectiveDate(), value.reason(), value.recordedBy(), value.recordedAt());
        });
    }

    private PrepaidExpensePlan read(ResultSet row) throws SQLException {
        UUID id = UUID.fromString(row.getString("id"));
        PrepaidPlanRequest request = new PrepaidPlanRequest(id, row.getString("reference"),
                new JournalLineKey(UUID.fromString(row.getString("source_entry_id")), row.getInt("source_line_no")),
                row.getString("expense_account_code"), YearMonth.parse(row.getString("first_month")), row.getInt("month_count"));
        List<PrepaidExpensePeriod> periods = context.query("SELECT * FROM acc_prepaid_schedule WHERE plan_id=? ORDER BY month_key",
                value -> new PrepaidExpensePeriod(YearMonth.parse(value.getString("month_key")), Money.of(value.getBigDecimal("amount"))), id);
        return new PrepaidExpensePlan(request, periods, row.getLong("created_by"), Instant.parse(row.getString("created_at")));
    }

    private PrepaidPlanCancellation readCancellation(ResultSet row) throws SQLException {
        return new PrepaidPlanCancellation(UUID.fromString(row.getString("plan_id")), row.getDate("effective_date").toLocalDate(),
                row.getString("reason"), row.getLong("recorded_by"), Instant.parse(row.getString("recorded_at")));
    }
}
