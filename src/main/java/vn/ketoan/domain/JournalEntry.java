package vn.ketoan.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import vn.ketoan.domain.value.Money;

/** An append-only ledger entry capturing exactly one approved document version. */
public final class JournalEntry {

    private final UUID id;
    private final UUID sourceDocumentId;
    private final long sourceVersion;
    private final VoucherSnapshot content;
    private final List<JournalLine> lines;
    private final long approvedBy;
    private final Instant approvedAt;
    private final long postedBy;
    private final Instant postedAt;

    private JournalEntry(UUID id, VoucherDocument approved, User actor, Instant time) {
        this.id = Objects.requireNonNull(id, "Journal id is required");
        approved.requireActionAllowed(actor, VoucherAction.POSTED);
        this.sourceDocumentId = approved.getId();
        this.sourceVersion = approved.getVersion();
        this.content = VoucherSnapshot.from(approved.getContent());
        this.approvedBy = approved.getApprovedBy().orElseThrow();
        this.approvedAt = approved.getApprovedAt().orElseThrow();
        this.postedBy = actor.getId();
        this.postedAt = Objects.requireNonNull(time, "Posting time is required");
        if (time.isBefore(approved.getUpdatedAt())) {
            throw new IllegalArgumentException("Posting time cannot precede approval");
        }
        List<JournalLine> numbered = new ArrayList<>();
        for (int index = 0; index < content.lines().size(); index++) {
            VoucherSnapshot.Line line = content.lines().get(index);
            numbered.add(new JournalLine(index + 1, line.accountCode(), line.side(), line.amount(),
                    line.partnerCode(), line.moneyAccountCode()));
        }
        this.lines = List.copyOf(numbered);
        if (lines.size() < 2 || !getTotalDebit().isPositive() || !getTotalDebit().equals(getTotalCredit())) {
            throw new IllegalArgumentException("A journal entry requires a positive balanced set of lines");
        }
    }

    public static JournalEntry fromApproved(UUID id, VoucherDocument approved, User actor, Instant time) {
        Objects.requireNonNull(approved, "Approved document is required");
        Objects.requireNonNull(actor, "Posting actor is required");
        return new JournalEntry(id, approved, actor, time);
    }

    public UUID getId() {
        return id;
    }

    public UUID getSourceDocumentId() {
        return sourceDocumentId;
    }

    public long getSourceVersion() {
        return sourceVersion;
    }

    public VoucherSnapshot getContent() {
        return content;
    }

    public LocalDate getPostingDate() {
        return content.postingDate();
    }

    public List<JournalLine> getLines() {
        return lines;
    }

    public Money getTotalDebit() {
        return lines.stream().map(JournalLine::getDebit).reduce(Money.ZERO, Money::add);
    }

    public Money getTotalCredit() {
        return lines.stream().map(JournalLine::getCredit).reduce(Money.ZERO, Money::add);
    }

    public long getApprovedBy() {
        return approvedBy;
    }

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public long getPostedBy() {
        return postedBy;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    public void requireMatches(VoucherDocument document) {
        if (document.getStatus() != VoucherStatus.POSTED || !sourceDocumentId.equals(document.getId())
                || sourceVersion != document.getVersion() - 1
                || !content.equals(VoucherSnapshot.from(document.getContent()))
                || approvedBy != document.getApprovedBy().orElseThrow()
                || !approvedAt.equals(document.getApprovedAt().orElseThrow())
                || postedBy != document.getPostedBy().orElseThrow()
                || !postedAt.equals(document.getPostedAt().orElseThrow())) {
            throw new IllegalStateException("Journal entry does not match its posted source document");
        }
    }
}
