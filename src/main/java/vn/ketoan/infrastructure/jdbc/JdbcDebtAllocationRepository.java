package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.DebtAllocationRepository;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.AllocationRequest;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.JournalLineKey;
import vn.ketoan.domain.value.Money;

final class JdbcDebtAllocationRepository implements DebtAllocationRepository {

    private final JdbcContext context;

    JdbcDebtAllocationRepository(JdbcContext context) {
        this.context = context;
    }

    @Override
    public Optional<DebtAllocation> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_debt_allocations WHERE id=?", this::read,
                Objects.requireNonNull(id, "Allocation id is required")));
    }

    @Override
    public List<DebtAllocation> findAll() {
        return context.query("SELECT * FROM acc_debt_allocations ORDER BY effective_date,id", this::read);
    }

    @Override
    public Optional<AllocationCancellation> findCancellation(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_allocation_cancellations WHERE allocation_id=?", this::readCancellation,
                Objects.requireNonNull(id, "Allocation id is required")));
    }

    @Override
    public List<AllocationCancellation> findCancellations() {
        return context.query("SELECT * FROM acc_allocation_cancellations ORDER BY effective_date,allocation_id", this::readCancellation);
    }

    @Override
    public void append(DebtAllocation value) {
        context.mutate(() -> {
            AllocationRequest request = value.request();
            context.update("""
                    INSERT INTO acc_debt_allocations(id,charge_entry_id,charge_line_no,settlement_entry_id,settlement_line_no,
                    amount,effective_date,note,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, value.id(), request.charge().journalEntryId(), request.charge().lineNumber(),
                    request.settlement().journalEntryId(), request.settlement().lineNumber(), request.amount(),
                    request.effectiveDate(), request.note(), value.recordedBy(), value.recordedAt());
        });
    }

    @Override
    public void appendCancellation(AllocationCancellation value) {
        context.mutate(() -> {
            findById(value.allocationId()).orElseThrow(() -> new IllegalArgumentException("Cancellation requires an existing allocation"));
            context.update("""
                    INSERT INTO acc_allocation_cancellations(allocation_id,effective_date,reason,recorded_by,recorded_at)
                    VALUES (?,?,?,?,?)
                    """, value.allocationId(), value.effectiveDate(), value.reason(), value.recordedBy(), value.recordedAt());
        });
    }

    private DebtAllocation read(ResultSet row) throws SQLException {
        AllocationRequest request = new AllocationRequest(UUID.fromString(row.getString("id")),
                new JournalLineKey(UUID.fromString(row.getString("charge_entry_id")), row.getInt("charge_line_no")),
                new JournalLineKey(UUID.fromString(row.getString("settlement_entry_id")), row.getInt("settlement_line_no")),
                Money.of(row.getBigDecimal("amount")), row.getDate("effective_date").toLocalDate(), row.getString("note"));
        return new DebtAllocation(request, row.getLong("recorded_by"), Instant.parse(row.getString("recorded_at")));
    }

    private AllocationCancellation readCancellation(ResultSet row) throws SQLException {
        return new AllocationCancellation(UUID.fromString(row.getString("allocation_id")), row.getDate("effective_date").toLocalDate(),
                row.getString("reason"), row.getLong("recorded_by"), Instant.parse(row.getString("recorded_at")));
    }
}
