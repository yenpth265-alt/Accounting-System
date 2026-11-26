package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.application.port.CashFlowClassificationRepository;
import vn.ketoan.domain.report.CashFlowActivity;
import vn.ketoan.domain.report.CashFlowClassification;
import vn.ketoan.domain.report.CashFlowDirection;
import vn.ketoan.domain.report.CashFlowSplit;
import vn.ketoan.domain.value.Money;

final class JdbcCashFlowClassificationRepository implements CashFlowClassificationRepository {
    private final JdbcContext context;
    JdbcCashFlowClassificationRepository(JdbcContext context) { this.context = context; }
    @Override public Optional<CashFlowClassification> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_cash_flow_classifications WHERE id=?", this::read, java.util.Objects.requireNonNull(id)));
    }
    @Override public List<CashFlowClassification> findAll() { return context.query("SELECT * FROM acc_cash_flow_classifications ORDER BY entry_id,revision_no", this::read); }
    @Override public void append(CashFlowClassification value) {
        context.mutate(() -> {
            context.update("INSERT INTO acc_cash_flow_classifications(id,entry_id,revision_no,reason,internal_transfer,recorded_by,recorded_at) VALUES (?,?,?,?,?,?,?)",
                    value.id(), value.entryId(), value.revision(), value.reason(), value.internalTransfer(), value.recordedBy(), value.recordedAt());
            for (int index = 0; index < value.splits().size(); index++) {
                var split = value.splits().get(index);
                context.update("INSERT INTO acc_cash_flow_splits(classification_id,position_no,activity,direction,amount) VALUES (?,?,?,?,?)", value.id(), index, split.activity(), split.direction(), split.amount());
            }
        });
    }
    private CashFlowClassification read(ResultSet row) throws SQLException {
        UUID id = UUID.fromString(row.getString("id"));
        var splits = context.query("SELECT * FROM acc_cash_flow_splits WHERE classification_id=? ORDER BY position_no", split -> new CashFlowSplit(
                CashFlowActivity.valueOf(split.getString("activity")), CashFlowDirection.valueOf(split.getString("direction")), Money.of(split.getBigDecimal("amount"))), id);
        return new CashFlowClassification(id, UUID.fromString(row.getString("entry_id")), row.getInt("revision_no"), row.getString("reason"),
                Money.of(row.getBigDecimal("internal_transfer")), splits, row.getLong("recorded_by"), Instant.parse(row.getString("recorded_at")));
    }
}
