package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

import vn.ketoan.domain.value.Money;

/** A debt or an amount available to settle debts, calculated for a statement date. */
public record OpenItem(JournalLineKey key, UUID sourceDocumentId, String reference, LocalDate postingDate,
                       String accountCode, String partnerCode, OpenItemKind kind, Money original, Money allocated) {

    public OpenItem {
        Objects.requireNonNull(key, "Journal line key is required");
        Objects.requireNonNull(sourceDocumentId, "Source document id is required");
        reference = DomainValidation.code(reference, 32, "Source reference");
        postingDate = DomainValidation.date(postingDate, "Posting date");
        accountCode = Account.normalizeCode(accountCode);
        partnerCode = Partner.normalizeCode(partnerCode);
        Objects.requireNonNull(kind, "Open item kind is required");
        Objects.requireNonNull(original, "Original amount is required");
        Objects.requireNonNull(allocated, "Allocated amount is required");
        if (!original.isPositive() || allocated.isNegative() || allocated.compareTo(original) > 0) {
            throw new IllegalArgumentException("Allocated amount must be between zero and the original amount");
        }
    }

    public Money remaining() {
        return original.subtract(allocated);
    }
}
