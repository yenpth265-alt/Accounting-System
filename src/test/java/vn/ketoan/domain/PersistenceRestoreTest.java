package vn.ketoan.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.content;
import static vn.ketoan.WorkflowTestData.user;

class PersistenceRestoreTest {

    @Test
    void restoresPostedVoucherAndJournalFromTheirImmutableFacts() {
        User owner = user(OWNER, UserRole.ACCOUNTANT);
        User chief = user(CHIEF, UserRole.CHIEF);
        VoucherDocument approved = VoucherDocument.create(UUID.randomUUID(), content(), owner, TIME)
                .submit(owner, TIME).approve(chief, TIME);
        VoucherDocument posted = approved.markPosted(chief, TIME);
        VoucherDocument restored = VoucherDocument.restore(posted.getId(), posted.getHistory());
        assertEquals(posted.getHistory(), restored.getHistory());
        assertEquals(VoucherStatus.POSTED, restored.getStatus());
        assertEquals(VoucherSnapshot.from(posted.getContent()), VoucherSnapshot.from(restored.getContent()));
        JournalEntry entry = JournalEntry.fromApproved(UUID.randomUUID(), approved, chief, TIME);
        JournalEntry restoredEntry = JournalEntry.restore(entry.getId(), entry.getSourceDocumentId(), entry.getSourceVersion(),
                entry.getContent(), entry.getApprovedBy(), entry.getApprovedAt(), entry.getPostedBy(), entry.getPostedAt());
        restoredEntry.requireMatches(restored);
    }

    @Test
    void rejectsMissingHistoryGapsAndUnauthorizedHistoricalTransitions() {
        User owner = user(OWNER, UserRole.ACCOUNTANT);
        VoucherDocument draft = VoucherDocument.create(UUID.randomUUID(), content(), owner, TIME);
        assertThrows(IllegalArgumentException.class, () -> VoucherDocument.restore(draft.getId(), List.of()));
        VoucherHistoryEntry created = draft.getHistory().getFirst();
        VoucherHistoryEntry skipped = new VoucherHistoryEntry(2, VoucherAction.SUBMITTED, OWNER,
                VoucherStatus.DRAFT, VoucherStatus.PENDING_APPROVAL, TIME, "Submitted", created.content());
        assertThrows(IllegalArgumentException.class, () -> VoucherDocument.restore(draft.getId(), List.of(created, skipped)));
        VoucherHistoryEntry pending = new VoucherHistoryEntry(1, VoucherAction.SUBMITTED, OWNER,
                VoucherStatus.DRAFT, VoucherStatus.PENDING_APPROVAL, TIME, "Submitted", created.content());
        List<VoucherHistoryEntry> invalid = new ArrayList<>(List.of(created, pending));
        invalid.add(new VoucherHistoryEntry(2, VoucherAction.APPROVED, OWNER, VoucherStatus.PENDING_APPROVAL,
                VoucherStatus.APPROVED, TIME, "Self approved", created.content()));
        assertThrows(IllegalArgumentException.class, () -> VoucherDocument.restore(draft.getId(), invalid));
    }
}
