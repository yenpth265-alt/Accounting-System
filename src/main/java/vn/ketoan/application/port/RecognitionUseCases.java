package vn.ketoan.application.port;

import java.util.UUID;

import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.VoucherDocument;

/** Complete draft entry only; submission, approval and ledger posting remain separate actions. */
public interface RecognitionUseCases {

    VoucherDocument createRevenue(RecognitionDraft input, long actorId);

    VoucherDocument createExpense(RecognitionDraft input, long actorId);

    VoucherDocument editRevenue(UUID documentId, long expectedVersion, RecognitionDraft input, long actorId);

    VoucherDocument editExpense(UUID documentId, long expectedVersion, RecognitionDraft input, long actorId);
}
