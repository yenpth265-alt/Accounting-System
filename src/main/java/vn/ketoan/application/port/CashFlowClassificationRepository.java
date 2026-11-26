package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import vn.ketoan.domain.report.CashFlowClassification;

public interface CashFlowClassificationRepository {
    Optional<CashFlowClassification> findById(UUID id);
    List<CashFlowClassification> findAll();
    void append(CashFlowClassification classification);
}
