package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.CorrectionRepository;
import vn.ketoan.domain.CorrectionRecord;

public final class InMemoryCorrectionRepository implements CorrectionRepository {

    private final Map<UUID, CorrectionRecord> records = new LinkedHashMap<>();

    @Override public Optional<CorrectionRecord> findById(UUID id) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(id, "Correction id is required")));
    }

    @Override public Optional<CorrectionRecord> findByTargetDocumentId(UUID id) {
        Objects.requireNonNull(id, "Target document id is required");
        return records.values().stream().filter(value -> value.targetDocumentId().equals(id)).findFirst();
    }

    @Override public List<CorrectionRecord> findAll() { return List.copyOf(records.values()); }

    @Override public void append(CorrectionRecord record) {
        Objects.requireNonNull(record, "Correction record is required");
        if (records.containsKey(record.id()) || findByTargetDocumentId(record.targetDocumentId()).isPresent()) {
            throw new IllegalStateException("Correction id or target document already exists");
        }
        records.put(record.id(), record);
    }
}
