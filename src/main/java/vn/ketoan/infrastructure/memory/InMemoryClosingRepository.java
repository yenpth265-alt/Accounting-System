package vn.ketoan.infrastructure.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.application.port.ClosingRepository;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.ClosingEvent;

public final class InMemoryClosingRepository implements ClosingRepository {
    private final Map<UUID, ClosingBatch> batches = new LinkedHashMap<>();
    private final List<ClosingEvent> events = new ArrayList<>();
    @Override public Optional<ClosingBatch> findById(UUID id) { return Optional.ofNullable(batches.get(Objects.requireNonNull(id))); }
    @Override public List<ClosingBatch> findAll() { return List.copyOf(batches.values()); }
    @Override public List<ClosingEvent> findEvents() { return List.copyOf(events); }
    @Override public void append(ClosingBatch batch) {
        Objects.requireNonNull(batch);
        if (batches.containsKey(batch.id()) || batches.values().stream().anyMatch(value -> value.request().reference().equals(batch.request().reference())
                || value.documentIds().stream().anyMatch(batch.documentIds()::contains))) {
            throw new IllegalArgumentException("Closing id, reference and document links must be unique");
        }
        batches.put(batch.id(), batch);
    }
    @Override public void appendEvent(ClosingEvent event) {
        Objects.requireNonNull(event);
        if (!batches.containsKey(event.batchId()) || events.stream().anyMatch(value -> value.batchId().equals(event.batchId()) && value.action() == event.action())) {
            throw new IllegalArgumentException("Closing event requires its batch and a unique action");
        }
        events.add(event);
    }
}
