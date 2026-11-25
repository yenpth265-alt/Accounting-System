package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.VoucherAction;
import vn.ketoan.domain.VoucherHistoryEntry;
import vn.ketoan.domain.VoucherSnapshot;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.VoucherType;
import vn.ketoan.domain.value.Money;

/** Maps current content, immutable history snapshots and immutable journal snapshots separately. */
final class JdbcSnapshots {

    private final JdbcContext context;

    JdbcSnapshots(JdbcContext context) {
        this.context = context;
    }

    VoucherSnapshot header(ResultSet row, String typeColumn, String referenceColumn,
                            List<VoucherSnapshot.Line> lines) throws SQLException {
        return new VoucherSnapshot(VoucherType.valueOf(row.getString(typeColumn)), row.getString(referenceColumn),
                row.getDate("document_date").toLocalDate(), row.getDate("posting_date").toLocalDate(),
                row.getString("description"), lines);
    }

    List<VoucherSnapshot.Line> currentLines(UUID id) {
        return checked(context.query("SELECT * FROM acc_voucher_lines WHERE voucher_id=? ORDER BY line_no", this::line, id));
    }

    List<VoucherSnapshot.Line> journalLines(UUID id) {
        return checked(context.query("SELECT * FROM acc_journal_lines WHERE entry_id=? ORDER BY line_no", this::line, id));
    }

    List<VoucherHistoryEntry> history(UUID id) {
        return context.query("SELECT * FROM acc_voucher_history WHERE voucher_id=? ORDER BY version_no", row -> {
            long version = row.getLong("version_no");
            List<VoucherSnapshot.Line> lines = checked(context.query(
                    "SELECT * FROM acc_history_lines WHERE voucher_id=? AND version_no=? ORDER BY line_no", this::line, id, version));
            String previous = row.getString("from_status");
            return new VoucherHistoryEntry(version, VoucherAction.valueOf(row.getString("action_code")), row.getLong("actor_id"),
                    previous == null ? null : VoucherStatus.valueOf(previous), VoucherStatus.valueOf(row.getString("to_status")),
                    Instant.parse(row.getString("occurred_at")), row.getString("note"),
                    header(row, "snapshot_type", "snapshot_reference", lines));
        }, id);
    }

    void insertCurrent(UUID id, VoucherSnapshot content) {
        insertLines(Lines.CURRENT, id, 0, content.lines());
    }

    void replaceCurrent(UUID id, VoucherSnapshot content) {
        context.update("DELETE FROM acc_voucher_lines WHERE voucher_id=?", id);
        insertCurrent(id, content);
    }

    void insertJournal(UUID id, VoucherSnapshot content) {
        insertLines(Lines.JOURNAL, id, 0, content.lines());
    }

    void appendHistory(UUID id, VoucherHistoryEntry event) {
        VoucherSnapshot content = event.content();
        context.update("""
                INSERT INTO acc_voucher_history(voucher_id,version_no,action_code,actor_id,from_status,to_status,occurred_at,
                note,snapshot_type,snapshot_reference,document_date,posting_date,description) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, event.version(), event.action(), event.actorId(), event.fromStatus(), event.toStatus(), event.occurredAt(),
                event.note(), content.type(), content.reference(), content.documentDate(), content.postingDate(), content.description());
        insertLines(Lines.HISTORY, id, event.version(), content.lines());
    }

    private void insertLines(Lines target, UUID id, long version, List<VoucherSnapshot.Line> lines) {
        int lineNumber = 0;
        for (VoucherSnapshot.Line line : lines) {
            lineNumber++;
            switch (target) {
                case CURRENT -> context.update("""
                        INSERT INTO acc_voucher_lines(voucher_id,line_no,account_code,side,amount,partner_code,money_account_code)
                        VALUES (?,?,?,?,?,?,?)
                        """, id, lineNumber, line.accountCode(), line.side(), line.amount(), line.partnerCode(), line.moneyAccountCode());
                case JOURNAL -> context.update("""
                        INSERT INTO acc_journal_lines(entry_id,line_no,account_code,side,amount,partner_code,money_account_code)
                        VALUES (?,?,?,?,?,?,?)
                        """, id, lineNumber, line.accountCode(), line.side(), line.amount(), line.partnerCode(), line.moneyAccountCode());
                case HISTORY -> context.update("""
                        INSERT INTO acc_history_lines(voucher_id,version_no,line_no,account_code,side,amount,partner_code,money_account_code)
                        VALUES (?,?,?,?,?,?,?,?)
                        """, id, version, lineNumber, line.accountCode(), line.side(), line.amount(), line.partnerCode(), line.moneyAccountCode());
            }
        }
    }

    private NumberedLine line(ResultSet row) throws SQLException {
        return new NumberedLine(row.getInt("line_no"), new VoucherSnapshot.Line(row.getString("account_code"),
                BalanceSide.valueOf(row.getString("side")), Money.of(row.getBigDecimal("amount")),
                row.getString("partner_code"), row.getString("money_account_code")));
    }

    private List<VoucherSnapshot.Line> checked(List<NumberedLine> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).number() != index + 1) {
                throw new IllegalStateException("Stored line numbers must be contiguous and start at one");
            }
        }
        return lines.stream().map(NumberedLine::content).toList();
    }

    private record NumberedLine(int number, VoucherSnapshot.Line content) { }

    private enum Lines { CURRENT, HISTORY, JOURNAL }
}
