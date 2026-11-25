package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.PrepaidAllocationRepository;
import vn.ketoan.domain.PrepaidAllocationRecord;

public final class InMemoryPrepaidAllocationRepository implements PrepaidAllocationRepository {

    private final Map<UUID, PrepaidAllocationRecord> records = new LinkedHashMap<>();

    @Override public Optional<PrepaidAllocationRecord> findById(UUID id) {
        return Optional.ofNullable(records.get(Objects.requireNonNull(id, "Prepaid allocation id is required")));
    }
    @Override public Optional<PrepaidAllocationRecord> findByTargetDocumentId(UUID id) {
        Objects.requireNonNull(id, "Target document id is required");
        return records.values().stream().filter(value -> value.targetDocumentId().equals(id)).findFirst();
    }
    @Override public List<PrepaidAllocationRecord> findAll() { return List.copyOf(records.values()); }
    @Override public void append(PrepaidAllocationRecord record) {
        Objects.requireNonNull(record, "Prepaid allocation record is required");
        if (records.containsKey(record.id()) || findByTargetDocumentId(record.targetDocumentId()).isPresent()) {
            throw new IllegalStateException("Prepaid allocation id or target already exists");
        }
        records.put(record.id(), record);
    }
}
