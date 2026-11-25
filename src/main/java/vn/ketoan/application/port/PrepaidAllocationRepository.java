package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.domain.PrepaidAllocationRecord;

public interface PrepaidAllocationRepository {

    Optional<PrepaidAllocationRecord> findById(UUID id);
    Optional<PrepaidAllocationRecord> findByTargetDocumentId(UUID id);
    List<PrepaidAllocationRecord> findAll();
    void append(PrepaidAllocationRecord record);
}
