package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.application.port.VoucherDocumentRepository;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;

/** Compare-and-save for one aggregate, including its append-only history. */
public final class InMemoryVoucherDocumentRepository implements VoucherDocumentRepository {

    private final Map<UUID, VoucherDocument> documents = new LinkedHashMap<>();
    private final Map<String, UUID> references = new LinkedHashMap<>();

    @Override
    public synchronized Optional<VoucherDocument> findById(UUID id) {
        return Optional.ofNullable(documents.get(Objects.requireNonNull(id, "Document id is required")));
    }

    @Override
    public synchronized List<VoucherDocument> findAll() {
        return List.copyOf(documents.values());
    }

    @Override
    public synchronized void insert(VoucherDocument document) {
        Objects.requireNonNull(document, "Document is required");
        if (document.getVersion() != 0 || document.getStatus() != VoucherStatus.DRAFT) {
            throw new IllegalArgumentException("Only a newly created draft can be inserted");
        }
        if (documents.containsKey(document.getId())
                || references.containsKey(document.getContent().getReference())) {
            throw new IllegalStateException("Document id or reference already exists");
        }
        documents.put(document.getId(), document);
        references.put(document.getContent().getReference(), document.getId());
    }

    @Override
    public synchronized void update(VoucherDocument document, long expectedVersion) {
        Objects.requireNonNull(document, "Document is required");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Expected version cannot be negative");
        }
        VoucherDocument current = documents.get(document.getId());
        if (current == null) {
            throw new IllegalArgumentException("Voucher document not found: " + document.getId());
        }
        if (current.getVersion() != expectedVersion) {
            throw new StaleVersionException(document.getId(), expectedVersion, current.getVersion());
        }
        if (document.getVersion() != Math.incrementExact(expectedVersion)
                || document.getCreatedBy() != current.getCreatedBy()
                || !document.getCreatedAt().equals(current.getCreatedAt())
                || document.getContent().getType() != current.getContent().getType()
                || !document.getContent().getReference().equals(current.getContent().getReference())) {
            throw new IllegalArgumentException("Update must preserve identity and advance exactly one version");
        }
        if (document.getHistory().size() != current.getHistory().size() + 1
                || !document.getHistory().subList(0, current.getHistory().size()).equals(current.getHistory())) {
            throw new IllegalArgumentException("Existing voucher history cannot be rewritten");
        }
        documents.put(document.getId(), document);
    }
}
