package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.ClosingEvent;

public interface ClosingRepository {
    Optional<ClosingBatch> findById(UUID id);
    List<ClosingBatch> findAll();
    void append(ClosingBatch batch);
    List<ClosingEvent> findEvents();
    void appendEvent(ClosingEvent event);
    default Optional<ClosingBatch> findByDocumentId(UUID id) {
        java.util.Objects.requireNonNull(id, "Closing document id is required");
        return findAll().stream().filter(batch -> batch.documentIds().contains(id)).findFirst();
    }
}
