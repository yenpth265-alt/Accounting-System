package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.application.port.VoucherDocumentRepository;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherSnapshot;
import vn.ketoan.domain.VoucherStatus;

final class JdbcVoucherDocumentRepository implements VoucherDocumentRepository {

    private final JdbcContext context;
    private final JdbcSnapshots snapshots;

    JdbcVoucherDocumentRepository(JdbcContext context) {
        this.context = context;
        this.snapshots = new JdbcSnapshots(context);
    }

    @Override
    public Optional<VoucherDocument> findById(UUID id) {
        return context.guard(() -> context.one("SELECT * FROM acc_vouchers WHERE id=?", this::read,
                Objects.requireNonNull(id, "Document id is required")));
    }

    @Override
    public List<VoucherDocument> findAll() {
        return context.query("SELECT * FROM acc_vouchers ORDER BY reference", this::read);
    }

    @Override
    public void insert(VoucherDocument value) {
        context.mutate(() -> {
            if (value.getVersion() != 0 || value.getStatus() != VoucherStatus.DRAFT) {
                throw new IllegalArgumentException("Only a newly created draft can be inserted");
            }
            VoucherSnapshot content = VoucherSnapshot.from(value.getContent());
            context.update("""
                    INSERT INTO acc_vouchers(id,reference,voucher_type,document_date,posting_date,description,created_by,
                    status,version_no,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """, value.getId(), content.reference(), content.type(), content.documentDate(), content.postingDate(),
                    content.description(), value.getCreatedBy(), value.getStatus(), value.getVersion(), value.getCreatedAt(), value.getUpdatedAt());
            snapshots.insertCurrent(value.getId(), content);
            snapshots.appendHistory(value.getId(), value.getHistory().getFirst());
        });
    }

    @Override
    public void update(VoucherDocument value, long expectedVersion) {
        context.mutate(() -> {
            if (expectedVersion < 0) { throw new IllegalArgumentException("Expected version cannot be negative"); }
            VoucherDocument current = findById(value.getId()).orElseThrow(
                    () -> new IllegalArgumentException("Voucher document not found: " + value.getId()));
            if (current.getVersion() != expectedVersion) {
                throw new StaleVersionException(value.getId(), expectedVersion, current.getVersion());
            }
            if (value.getVersion() != Math.incrementExact(expectedVersion) || value.getCreatedBy() != current.getCreatedBy()
                    || !value.getCreatedAt().equals(current.getCreatedAt()) || value.getContent().getType() != current.getContent().getType()
                    || !value.getContent().getReference().equals(current.getContent().getReference())
                    || value.getHistory().size() != current.getHistory().size() + 1
                    || !value.getHistory().subList(0, current.getHistory().size()).equals(current.getHistory())) {
                throw new IllegalArgumentException("Update must preserve identity and history and advance exactly one version");
            }
            VoucherSnapshot content = VoucherSnapshot.from(value.getContent());
            int changed = context.update("""
                    UPDATE acc_vouchers SET document_date=?,posting_date=?,description=?,status=?,version_no=?,updated_at=?
                    WHERE id=? AND version_no=?
                    """, content.documentDate(), content.postingDate(), content.description(), value.getStatus(), value.getVersion(),
                    value.getUpdatedAt(), value.getId(), expectedVersion);
            if (changed != 1) {
                long actual = context.one("SELECT version_no FROM acc_vouchers WHERE id=?", row -> row.getLong(1), value.getId())
                        .orElseThrow(() -> new IllegalStateException("Voucher disappeared during update"));
                throw new StaleVersionException(value.getId(), expectedVersion, actual);
            }
            if (!content.equals(VoucherSnapshot.from(current.getContent()))) {
                snapshots.replaceCurrent(value.getId(), content);
            }
            snapshots.appendHistory(value.getId(), value.getHistory().getLast());
        });
    }

    private VoucherDocument read(ResultSet row) throws SQLException {
        UUID id = UUID.fromString(row.getString("id"));
        VoucherDocument document = VoucherDocument.restore(id, snapshots.history(id));
        VoucherSnapshot content = snapshots.header(row, "voucher_type", "reference", snapshots.currentLines(id));
        if (!content.equals(VoucherSnapshot.from(document.getContent())) || document.getCreatedBy() != row.getLong("created_by")
                || document.getStatus() != VoucherStatus.valueOf(row.getString("status")) || document.getVersion() != row.getLong("version_no")
                || !document.getCreatedAt().equals(Instant.parse(row.getString("created_at")))
                || !document.getUpdatedAt().equals(Instant.parse(row.getString("updated_at")))) {
            throw new IllegalStateException("Stored voucher header or lines do not match the audit history");
        }
        return document;
    }
}
