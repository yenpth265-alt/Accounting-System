package vn.ketoan.domain.report;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLine;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.value.Money;

/** A traceable, immutable report projection of one posted ledger line. */
public record JournalRow(UUID entryId, UUID documentId, int lineNumber, LocalDate postingDate, Instant postedAt,
                         String reference, String description, String accountCode, String partnerCode,
                         String moneyAccountCode, DebitCredit amounts, boolean closing) {
    public JournalRow {
        Objects.requireNonNull(entryId, "Journal id is required");
        Objects.requireNonNull(documentId, "Document id is required");
        Objects.requireNonNull(postedAt, "Posting audit time is required");
        ReportPeriod.requireDate(postingDate);
        Objects.requireNonNull(reference, "Reference is required");
        Objects.requireNonNull(description, "Description is required");
        accountCode = Account.normalizeCode(accountCode);
        partnerCode = partnerCode == null ? null : Partner.normalizeCode(partnerCode);
        moneyAccountCode = moneyAccountCode == null ? null : MoneyAccount.normalizeCode(moneyAccountCode);
        Objects.requireNonNull(amounts, "Line amounts are required");
        if (lineNumber < 1 || amounts.debit().isPositive() == amounts.credit().isPositive()) {
            throw new IllegalArgumentException("A journal report row requires one positive posting side");
        }
    }
    public static JournalRow from(JournalEntry entry, JournalLine line, boolean closing) {
        return new JournalRow(entry.getId(), entry.getSourceDocumentId(), line.getLineNumber(), entry.getPostingDate(), entry.getPostedAt(),
                entry.getContent().reference(), entry.getContent().description(), line.getAccountCode(), line.getPartnerCode().orElse(null),
                line.getMoneyAccountCode().orElse(null), new DebitCredit(line.getDebit(), line.getCredit()), closing);
    }
    public Money net() { return amounts.net(); }
}
