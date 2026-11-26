package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.application.port.CashFlowClassificationRepository;
import vn.ketoan.domain.report.CashFlowClassification;

public final class InMemoryCashFlowClassificationRepository implements CashFlowClassificationRepository {
    private final Map<UUID, CashFlowClassification> values = new LinkedHashMap<>();
    @Override public Optional<CashFlowClassification> findById(UUID id) { return Optional.ofNullable(values.get(java.util.Objects.requireNonNull(id))); }
    @Override public List<CashFlowClassification> findAll() { return List.copyOf(values.values()); }
    @Override public void append(CashFlowClassification value) {
        if (values.containsKey(value.id()) || values.values().stream().anyMatch(existing -> existing.entryId().equals(value.entryId()) && existing.revision() == value.revision())) {
            throw new IllegalArgumentException("Cash flow classification id and revision must be unique");
        }
        values.put(value.id(), value);
    }
}
