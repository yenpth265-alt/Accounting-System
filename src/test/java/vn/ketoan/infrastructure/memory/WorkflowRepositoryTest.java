package vn.ketoan.infrastructure.memory;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherDocument;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.content;
import static vn.ketoan.WorkflowTestData.receiptDraft;
import static vn.ketoan.WorkflowTestData.user;

class WorkflowRepositoryTest {

    private final User owner = user(OWNER, UserRole.ACCOUNTANT);

    @Test
    void staleSaveCannotOverwriteContentOrHistory() {
        InMemoryVoucherDocumentRepository repository = new InMemoryVoucherDocumentRepository();
        VoucherDocument original = create(UUID.randomUUID());
        repository.insert(original);
        VoucherDocument first = original.edit(owner,
                new ReceiptCreator().create(receiptDraft("WF001", 5_000_000)), TIME);
        VoucherDocument stale = original.submit(owner, TIME);
        repository.update(first, 0);

        assertThrows(StaleVersionException.class, () -> repository.update(stale, 0));
        assertEquals(first, repository.findById(original.getId()).orElseThrow());
        assertEquals(2, first.getHistory().size());
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void concurrentUpdatesAllowExactlyOneWinnerAndOneHistoryAppend() throws Exception {
        InMemoryVoucherDocumentRepository repository = new InMemoryVoucherDocumentRepository();
        VoucherDocument original = create(UUID.randomUUID());
        repository.insert(original);
        VoucherDocument first = original.edit(owner,
                new ReceiptCreator().create(receiptDraft("WF001", 5_000_000)), TIME);
        VoucherDocument second = original.edit(owner,
                new ReceiptCreator().create(receiptDraft("WF001", 6_000_000)), TIME);
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Boolean> a = executor.submit(() -> attemptSave(repository, first, start));
            Future<Boolean> b = executor.submit(() -> attemptSave(repository, second, start));
            start.countDown();
            int winners = (a.get(10, TimeUnit.SECONDS) ? 1 : 0) + (b.get(10, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, winners);
        }
        VoucherDocument stored = repository.findById(original.getId()).orElseThrow();
        assertEquals(1, stored.getVersion());
        assertEquals(2, stored.getHistory().size());
    }

    @Test
    void aNewBranchCannotRewriteExistingAuditEvidence() {
        InMemoryVoucherDocumentRepository repository = new InMemoryVoucherDocumentRepository();
        VoucherDocument original = create(UUID.randomUUID());
        repository.insert(original);
        VoucherDocument forgedBranch = VoucherDocument.create(original.getId(),
                new ReceiptCreator().create(receiptDraft("WF001", 9_000_000)), owner, TIME)
                .submit(owner, TIME);

        assertThrows(IllegalArgumentException.class, () -> repository.update(forgedBranch, 0));
        assertEquals(original, repository.findById(original.getId()).orElseThrow());
    }

    @Test
    void duplicateIdentifiersAndReferencesAreRejectedWithoutPartialInsert() {
        InMemoryVoucherDocumentRepository repository = new InMemoryVoucherDocumentRepository();
        VoucherDocument original = create(UUID.randomUUID());
        repository.insert(original);

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> repository.insert(original)),
                () -> assertThrows(IllegalStateException.class, () -> repository.insert(create(UUID.randomUUID()))),
                () -> assertThrows(UnsupportedOperationException.class, repository.findAll()::clear));
        assertEquals(1, repository.findAll().size());
        assertTrue(repository.findById(UUID.randomUUID()).isEmpty());
    }

    @Test
    void updateCannotInsertAMissingDocumentOrSkipVersions() {
        InMemoryVoucherDocumentRepository repository = new InMemoryVoucherDocumentRepository();
        VoucherDocument original = create(UUID.randomUUID());
        assertThrows(IllegalArgumentException.class, () -> repository.update(original.submit(owner, TIME), 0));
        assertThrows(IllegalArgumentException.class, () -> repository.insert(original.submit(owner, TIME)));
        repository.insert(original);
        VoucherDocument skipped = original.submit(owner, TIME)
                .returnForRevision(user(2, UserRole.CHIEF), "Reason", TIME);

        assertThrows(IllegalArgumentException.class, () -> repository.update(skipped, 0));
        assertEquals(original, repository.findById(original.getId()).orElseThrow());
    }

    @Test
    void userStorageProtectsUniqueNamesAndReturnsImmutableSnapshots() {
        InMemoryUserRepository users = new InMemoryUserRepository();
        users.insert(owner);

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> users.insert(owner)),
                () -> assertThrows(IllegalStateException.class,
                        () -> users.insert(new User(2, " USER1 ", "Duplicate", owner.getRoles()))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> users.update(user(99, UserRole.VIEWER))),
                () -> assertThrows(UnsupportedOperationException.class, users.findAll()::clear));
        users.insert(user(2, UserRole.VIEWER));
        assertThrows(IllegalStateException.class,
                () -> users.update(new User(2, "USER1", "Duplicate", owner.getRoles())));
        assertEquals("user2", users.findById(2).orElseThrow().getUsername());
    }

    private boolean attemptSave(InMemoryVoucherDocumentRepository repository, VoucherDocument document,
                                CountDownLatch start) throws InterruptedException {
        start.await();
        try {
            repository.update(document, 0);
            return true;
        } catch (StaleVersionException exception) {
            return false;
        }
    }

    private VoucherDocument create(UUID id) {
        return VoucherDocument.create(id, content(), owner, TIME);
    }
}
