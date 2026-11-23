package vn.ketoan.application.service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.User;
import vn.ketoan.domain.VoucherAction;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.approved;
import static vn.ketoan.PostingTestData.named;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.receipt;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.COMBINED;
import static vn.ketoan.WorkflowTestData.OTHER;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.VIEWER;

class PostingServiceTest {

    private InMemoryUnitOfWork transactions;
    private PostingService posting;
    private VoucherDocument approved;

    @BeforeEach
    void setUp() {
        transactions = transactions();
        posting = posting(transactions);
        approved = approved(transactions, new ReceiptCreator(), receipt());
    }

    @Test
    void postsBalancedNumberedRowsAndPreservesApprovalMetadata() {
        JournalEntry entry = posting.post(approved.getId(), approved.getVersion(), CHIEF);
        VoucherDocument stored = stored();

        assertEquals(VoucherStatus.POSTED, stored.getStatus());
        assertEquals(approved.getVersion() + 1, stored.getVersion());
        assertEquals(CHIEF, stored.getApprovedBy().orElseThrow());
        assertEquals(TIME, stored.getApprovedAt().orElseThrow());
        assertEquals(CHIEF, stored.getPostedBy().orElseThrow());
        assertEquals(VoucherAction.POSTED, stored.getHistory().getLast().action());
        assertEquals(Money.of(4_000_000), entry.getTotalDebit());
        assertEquals(entry.getTotalDebit(), entry.getTotalCredit());
        assertEquals(1, entry.getLines().getFirst().getLineNumber());
        assertEquals(2, entry.getLines().getLast().getLineNumber());
        assertEquals("KH01", entry.getLines().getLast().getPartnerCode().orElseThrow());
        assertThrows(UnsupportedOperationException.class, entry.getLines()::clear);
        entry.requireMatches(stored);
    }

    @Test
    void repeatsReturnTheSameEntryWithoutAddingHistoryOrAmounts() {
        JournalEntry first = posting.post(approved.getId(), approved.getVersion(), CHIEF);
        JournalEntry repeated = posting.post(approved.getId(), approved.getVersion(), CHIEF);
        JournalEntry currentVersion = posting.post(approved.getId(), stored().getVersion(), CHIEF);

        assertEquals(first.getId(), repeated.getId());
        assertEquals(first.getId(), currentVersion.getId());
        assertEquals(1, journalSize());
        assertEquals(approved.getHistory().size() + 1, stored().getHistory().size());
    }

    @Test
    void completedPostingCanBeRetriedAfterItsPeriodAndCatalogAreClosed() {
        JournalEntry first = posting.post(approved.getId(), approved.getVersion(), CHIEF);
        transactions.execute(session -> {
            AccountingPeriod period = session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow();
            session.periods().update(period.beginClosing().completeClosing());
            session.moneyAccounts().update(session.moneyAccounts().findByCode("CASH01").orElseThrow().deactivate());
            return null;
        });
        assertEquals(first.getId(), posting.post(approved.getId(), approved.getVersion(), CHIEF).getId());
        assertEquals(1, journalSize());
    }

    @ParameterizedTest
    @ValueSource(longs = {OWNER, OTHER, VIEWER, ADMIN})
    void unauthorizedUsersCannotPostOrUseTheRetryPath(long actorId) {
        assertThrows(AccessDeniedException.class, () -> posting.post(approved.getId(), approved.getVersion(), actorId));
        assertEquals(0, journalSize());
        posting.post(approved.getId(), approved.getVersion(), CHIEF);
        assertThrows(AccessDeniedException.class, () -> posting.post(approved.getId(), approved.getVersion(), actorId));
        assertEquals(1, journalSize());
    }

    @Test
    void creatorCannotPostTheirOwnDocumentEvenWithCombinedRoles() {
        VoucherDocument combined = transactions.execute(session -> {
            VoucherWorkflowService workflow = workflow(session);
            VoucherDocument draft = workflow.create(new ReceiptCreator(),
                    named("COMBINED", receipt()), COMBINED);
            VoucherDocument pending = workflow.submit(draft.getId(), 0, COMBINED);
            return workflow.approve(pending.getId(), pending.getVersion(), CHIEF);
        });
        assertThrows(AccessDeniedException.class, () -> posting.post(combined.getId(), combined.getVersion(), COMBINED));
        assertEquals(0, journalSize());
    }

    @Test
    void unapprovedOrStaleDocumentsCannotBePosted() {
        VoucherDocument draft = transactions.execute(session -> workflow(session).create(new ReceiptCreator(),
                named("DRAFT", receipt()), OWNER));
        assertThrows(IllegalStateException.class, () -> posting.post(draft.getId(), 0, CHIEF));
        assertThrows(StaleVersionException.class, () -> posting.post(approved.getId(), 0, CHIEF));
        assertEquals(0, journalSize());
        assertEquals(approved, stored());
    }

    @Test
    void postingRechecksFundAndPeriodAndChangesNothingOnFailure() {
        MoneyAccount original = transactions.execute(session -> session.moneyAccounts().findByCode("CASH01").orElseThrow());
        transactions.execute(session -> { session.moneyAccounts().update(original.deactivate()); return null; });
        assertThrows(IllegalStateException.class, () -> posting.post(approved.getId(), approved.getVersion(), CHIEF));
        assertEquals(approved, stored());
        assertEquals(0, journalSize());
        transactions.execute(session -> {
            session.moneyAccounts().update(original);
            AccountingPeriod period = session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow();
            session.periods().update(period.beginClosing().completeClosing());
            return null;
        });
        assertThrows(IllegalStateException.class, () -> posting.post(approved.getId(), approved.getVersion(), CHIEF));
        assertEquals(approved, stored());
        assertEquals(0, journalSize());
    }

    @Test
    void disabledPostingUserIsRejectedAndClockCannotPrecedeApproval() {
        User chief = transactions.execute(session -> session.users().findById(CHIEF).orElseThrow());
        transactions.execute(session -> { session.users().update(chief.deactivate()); return null; });
        assertThrows(AccessDeniedException.class, () -> posting.post(approved.getId(), approved.getVersion(), CHIEF));
        transactions.execute(session -> { session.users().update(chief); return null; });
        PostingService oldClock = new PostingService(transactions, validators(),
                Clock.fixed(TIME.minusSeconds(1), ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class, () -> oldClock.post(approved.getId(), approved.getVersion(), CHIEF));
        assertEquals(approved, stored());
        assertEquals(0, journalSize());
    }

    @Test
    void concurrentRetriesProduceOneJournalEntryAndOnePostedEvent() throws Exception {
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<JournalEntry> a = executor.submit(() -> posting.post(approved.getId(), approved.getVersion(), CHIEF));
            Future<JournalEntry> b = executor.submit(() -> posting.post(approved.getId(), approved.getVersion(), CHIEF));
            assertEquals(a.get(10, TimeUnit.SECONDS).getId(), b.get(10, TimeUnit.SECONDS).getId());
        }
        assertEquals(1, journalSize());
        assertEquals(approved.getHistory().size() + 1, stored().getHistory().size());
    }

    @Test
    void postedContentCannotBeEditedCancelledOrHaveApprovalWithdrawn() {
        posting.post(approved.getId(), approved.getVersion(), CHIEF);
        VoucherDocument posted = stored();
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> workflow(session)
                .edit(posted.getId(), posted.getVersion(), new ReceiptCreator(), receipt(), OWNER)));
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> workflow(session)
                .withdrawApproval(posted.getId(), posted.getVersion(), CHIEF, "Reason")));
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> workflow(session)
                .cancel(posted.getId(), posted.getVersion(), OWNER, "Reason")));
        assertEquals(posted, stored());
    }

    @Test
    void invalidIdentifiersAndVersionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> posting.post(approved.getId(), -1, CHIEF));
        assertThrows(IllegalArgumentException.class, () -> posting.post(UUID.randomUUID(), 0, CHIEF));
        assertThrows(AccessDeniedException.class, () -> posting.post(approved.getId(), approved.getVersion(), 999));
        JournalEntry entry = posting.post(approved.getId(), approved.getVersion(), CHIEF);
        assertThrows(StaleVersionException.class, () -> posting.post(approved.getId(), 0, CHIEF));
        assertTrue(entry.getPostedAt().equals(TIME));
    }

    private VoucherDocument stored() {
        return transactions.execute(session -> session.documents().findById(approved.getId()).orElseThrow());
    }

    private int journalSize() {
        return transactions.execute(session -> session.journal().findAll().size());
    }
}
