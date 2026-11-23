package vn.ketoan.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.content;
import static vn.ketoan.WorkflowTestData.user;

class JournalEntryTest {

    @Test
    void requiresApprovalAndAnIndependentPostingActor() {
        User owner = user(OWNER, UserRole.ACCOUNTANT);
        User chief = user(CHIEF, UserRole.CHIEF);
        VoucherDocument draft = VoucherDocument.create(UUID.randomUUID(), content(), owner, TIME);
        assertThrows(IllegalStateException.class,
                () -> JournalEntry.fromApproved(UUID.randomUUID(), draft, chief, TIME));
        VoucherDocument approved = draft.submit(owner, TIME).approve(chief, TIME);
        assertThrows(AccessDeniedException.class,
                () -> JournalEntry.fromApproved(UUID.randomUUID(), approved, owner, TIME));
        assertThrows(IllegalArgumentException.class,
                () -> JournalEntry.fromApproved(UUID.randomUUID(), approved, chief, TIME.minusSeconds(1)));
    }

    @Test
    void recordsTheLatestApprovalAndKeepsPostingMetadataSeparate() {
        User owner = user(OWNER, UserRole.ACCOUNTANT);
        User chief = user(CHIEF, UserRole.CHIEF);
        User secondChief = user(7, UserRole.CHIEF);
        VoucherDocument approved = VoucherDocument.create(UUID.randomUUID(), content(), owner, TIME)
                .submit(owner, TIME).approve(chief, TIME)
                .withdrawApproval(chief, "Review again", TIME)
                .submit(owner, TIME).approve(secondChief, TIME.plusSeconds(1));
        JournalEntry entry = JournalEntry.fromApproved(UUID.randomUUID(), approved, chief, TIME.plusSeconds(2));
        VoucherDocument posted = approved.markPosted(chief, TIME.plusSeconds(2));

        assertEquals(7, entry.getApprovedBy());
        assertEquals(TIME.plusSeconds(1), entry.getApprovedAt());
        assertEquals(CHIEF, entry.getPostedBy());
        assertEquals(7, posted.getApprovedBy().orElseThrow());
        assertEquals(CHIEF, posted.getPostedBy().orElseThrow());
        assertEquals(approved.getContent().getPostingDate(), entry.getPostingDate());
        entry.requireMatches(posted);
        assertThrows(IllegalStateException.class, () -> entry.requireMatches(approved));
    }

    @Test
    void journalRowsRetainDraftLineValidationAndNumbering() {
        JournalLine line = new JournalLine(1, "131", BalanceSide.DEBIT, Money.of(10), "KH01", null);
        assertEquals(Money.of(10), line.getAmount());
        assertEquals(BalanceSide.DEBIT, line.getSide());
        assertEquals(Money.ZERO, line.getCredit());
        assertThrows(IllegalArgumentException.class,
                () -> new JournalLine(0, "131", BalanceSide.DEBIT, Money.of(10), "KH01", null));
        assertThrows(IllegalArgumentException.class,
                () -> new JournalLine(1, "131", BalanceSide.DEBIT, Money.ZERO, "KH01", null));
    }
}
