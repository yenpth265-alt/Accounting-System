package vn.ketoan.application.port;

import java.util.List;

import vn.ketoan.domain.AdjustmentRequest;
import vn.ketoan.domain.CorrectionRecord;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.VoucherDocument;

public interface CorrectionUseCases {

    VoucherDocument createAdjustment(AdjustmentRequest request, long actorId);
    VoucherDocument createReversal(ReversalRequest request, long actorId);
    List<CorrectionRecord> history(long actorId);
}
