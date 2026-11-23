package vn.ketoan.infrastructure.memory;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.UserRepository;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.User;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.approved;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.VoucherTestData.receipt;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;

class InMemoryUnitOfWorkTest {

    @Test
    void exceptionsAndErrorsDiscardCatalogChanges() {
        InMemoryUnitOfWork transactions = transactions();
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            Account account = session.accounts().findByCode("1111").orElseThrow();
            session.accounts().update(account.deactivate());
            throw new IllegalStateException("Simulated failure");
        }));
        assertThrows(AssertionError.class, () -> transactions.execute(session -> {
            User user = session.users().findById(OWNER).orElseThrow();
            session.users().update(user.deactivate());
            throw new AssertionError("Simulated fatal failure");
        }));
        assertTrue(transactions.<Boolean>execute(session -> session.accounts().findByCode("1111").orElseThrow().isActive()));
        assertTrue(transactions.<Boolean>execute(session -> session.users().findById(OWNER).orElseThrow().isActive()));
    }

    @Test
    void failureAfterJournalAppendLeavesNeitherPostingNorHistoryChanges() {
        InMemoryUnitOfWork transactions = transactions();
        VoucherDocument approved = approved(transactions, new ReceiptCreator(), receipt());
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            JournalEntry entry = entry(session, approved);
            session.journal().append(entry);
            throw new IllegalStateException("Interrupted after journal write");
        }));
        assertEquals(0, transactions.<Integer>execute(session -> session.journal().findAll().size()));
        assertEquals(approved, transactions.execute(session -> session.documents().findById(approved.getId()).orElseThrow()));
        posting(transactions).post(approved.getId(), approved.getVersion(), CHIEF);
        assertEquals(1, transactions.<Integer>execute(session -> session.journal().findAll().size()));
    }

    @Test
    void failureAfterAllWritesRollsBackJournalDocumentAndAuditTogether() {
        InMemoryUnitOfWork transactions = transactions();
        VoucherDocument approved = approved(transactions, new ReceiptCreator(), receipt());
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            User chief = session.users().findById(CHIEF).orElseThrow();
            session.journal().append(entry(session, approved));
            session.documents().update(approved.markPosted(chief, TIME), approved.getVersion());
            throw new IllegalStateException("Interrupted before commit");
        }));
        assertEquals(0, transactions.<Integer>execute(session -> session.journal().findAll().size()));
        VoucherDocument stored = transactions.execute(session -> session.documents().findById(approved.getId()).orElseThrow());
        assertEquals(VoucherStatus.APPROVED, stored.getStatus());
        assertEquals(approved.getHistory(), stored.getHistory());
    }

    @Test
    void commitRejectsEitherHalfOfAnIncompletePosting() {
        InMemoryUnitOfWork transactions = transactions();
        VoucherDocument approved = approved(transactions, new ReceiptCreator(), receipt());
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.journal().append(entry(session, approved));
            return null;
        }));
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.documents().update(approved.markPosted(session.users().findById(CHIEF).orElseThrow(), TIME),
                    approved.getVersion());
            return null;
        }));
        assertEquals(approved, transactions.execute(session -> session.documents().findById(approved.getId()).orElseThrow()));
        assertEquals(0, transactions.<Integer>execute(session -> session.journal().findAll().size()));
    }

    @Test
    void caughtRepositoryFailureStillMarksTheWholeTransactionRollbackOnly() {
        InMemoryUnitOfWork transactions = transactions();
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            Account original = session.accounts().findByCode("1111").orElseThrow();
            session.accounts().update(original.deactivate());
            try {
                session.accounts().insert(original);
            } catch (IllegalStateException expected) {
                // A caught repository error must not accidentally commit the earlier update.
            }
            return null;
        }));
        assertTrue(transactions.<Boolean>execute(session -> session.accounts().findByCode("1111").orElseThrow().isActive()));
    }

    @Test
    void nestedTransactionsCannotOverwriteTheOuterTransaction() {
        InMemoryUnitOfWork transactions = transactions();
        assertThrows(IllegalStateException.class, () -> transactions.execute(session -> {
            session.users().update(session.users().findById(OWNER).orElseThrow().deactivate());
            try {
                transactions.execute(inner -> null);
            } catch (IllegalStateException expected) {
                // The outer transaction is now rollback-only too.
            }
            return null;
        }));
        assertTrue(transactions.<Boolean>execute(session -> session.users().findById(OWNER).orElseThrow().isActive()));
    }

    @Test
    void sessionAndRepositoryHandlesCannotBeUsedAfterCommit() {
        InMemoryUnitOfWork transactions = transactions();
        AtomicReference<AccountingSession> escapedSession = new AtomicReference<>();
        AtomicReference<UserRepository> escapedUsers = new AtomicReference<>();
        transactions.execute(session -> {
            escapedSession.set(session);
            escapedUsers.set(session.users());
            return null;
        });
        assertThrows(IllegalStateException.class, () -> escapedSession.get().users());
        assertThrows(IllegalStateException.class, () -> escapedUsers.get().findAll());
    }

    @Test
    void repositoryHandlesCannotBeUsedByAnotherThread() {
        InMemoryUnitOfWork transactions = transactions();
        transactions.execute(session -> {
            UserRepository users = session.users();
            try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
                Future<Boolean> result = executor.submit(() -> {
                    try {
                        users.findAll();
                        return false;
                    } catch (IllegalStateException expected) {
                        return true;
                    }
                });
                assertTrue(result.get(10, TimeUnit.SECONDS));
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
            return null;
        });
    }

    private JournalEntry entry(AccountingSession session, VoucherDocument approved) {
        return JournalEntry.fromApproved(UUID.randomUUID(), approved,
                session.users().findById(CHIEF).orElseThrow(), TIME);
    }
}
