package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.JournalRepository;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.VoucherSnapshot;

final class JdbcJournalRepository implements JournalRepository {

    private final JdbcContext context;
    private final JdbcSnapshots snapshots;

    JdbcJournalRepository(JdbcContext context) {
        this.context = context;
        this.snapshots = new JdbcSnapshots(context);
    }

    @Override
    public Optional<JournalEntry> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_journal_entries WHERE id=?", this::read,
                Objects.requireNonNull(id, "Journal id is required")));
    }

    @Override
    public Optional<JournalEntry> findBySourceDocumentId(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_journal_entries WHERE source_document_id=?", this::read,
                Objects.requireNonNull(id, "Source document id is required")));
    }

    @Override
    public List<JournalEntry> findAll() {
        return context.query("SELECT * FROM acc_journal_entries ORDER BY posting_date,reference", this::read);
    }

    @Override
    public void append(JournalEntry value) {
        context.mutate(() -> {
            VoucherSnapshot content = value.getContent();
            context.update("""
                    INSERT INTO acc_journal_entries(id,source_document_id,source_version,voucher_type,reference,document_date,
                    posting_date,description,approved_by,approved_at,posted_by,posted_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                    """, value.getId(), value.getSourceDocumentId(), value.getSourceVersion(), content.type(), content.reference(),
                    content.documentDate(), content.postingDate(), content.description(), value.getApprovedBy(), value.getApprovedAt(),
                    value.getPostedBy(), value.getPostedAt());
            snapshots.insertJournal(value.getId(), content);
        });
    }

    private JournalEntry read(ResultSet row) throws SQLException {
        UUID id = UUID.fromString(row.getString("id"));
        VoucherSnapshot content = snapshots.header(row, "voucher_type", "reference", snapshots.journalLines(id));
        return JournalEntry.restore(id, UUID.fromString(row.getString("source_document_id")), row.getLong("source_version"),
                content, row.getLong("approved_by"), Instant.parse(row.getString("approved_at")),
                row.getLong("posted_by"), Instant.parse(row.getString("posted_at")));
    }
}
