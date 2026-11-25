package vn.ketoan.application.port;

import java.util.List;
import java.util.UUID;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.ClosingEvent;
import vn.ketoan.domain.ClosingPreview;
import vn.ketoan.domain.ClosingRequest;

public interface ClosingUseCases {
    ClosingPreview preview(ClosingRequest request, long actorId);
    ClosingBatch prepare(ClosingRequest request, long actorId);
    ClosingBatch approve(UUID batchId, long actorId);
    ClosingBatch close(UUID batchId, long actorId);
    ClosingBatch cancel(UUID batchId, String reason, long actorId);
    List<ClosingBatch> batches(long actorId);
    List<ClosingEvent> history(UUID batchId, long actorId);
}
