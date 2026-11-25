package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

public record ReversalRequest(UUID id, UUID sourceDocumentId, String reference, LocalDate postingDate, String reason) {

    public ReversalRequest {
        Objects.requireNonNull(id, "Reversal request id is required");
        Objects.requireNonNull(sourceDocumentId, "Source document id is required");
        reference = DomainValidation.code(reference, 32, "Reversal reference");
        postingDate = DomainValidation.date(postingDate, "Reversal posting date");
        reason = DomainValidation.requiredText(reason, 255, "Reversal reason");
    }

    public Draft toDraft(JournalEntry source) {
        return new Draft(reference, postingDate, postingDate, reason, source.getLines().stream()
                .map(line -> new VoucherLine(line.getAccountCode(),
                        line.getSide() == BalanceSide.DEBIT ? BalanceSide.CREDIT : BalanceSide.DEBIT,
                        line.getAmount(), line.getPartnerCode().orElse(null), line.getMoneyAccountCode().orElse(null)))
                .toList());
    }
}
