package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.domain.CorrectionRecord;

public interface CorrectionRepository {

    Optional<CorrectionRecord> findById(UUID id);
    Optional<CorrectionRecord> findByTargetDocumentId(UUID id);
    List<CorrectionRecord> findAll();
    void append(CorrectionRecord record);
}
