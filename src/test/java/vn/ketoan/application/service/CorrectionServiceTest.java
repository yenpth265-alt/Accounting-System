package vn.ketoan.application.service;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.AdjustmentRequest;
import vn.ketoan.domain.CorrectionKind;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherSnapshot;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.CorrectionTestData.approve;
import static vn.ketoan.CorrectionTestData.corrections;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.SettlementTestData.allocations;
import static vn.ketoan.SettlementTestData.postDraft;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.SettlementTestData.request;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class CorrectionServiceTest {

    private InMemoryUnitOfWork transactions;
    private CorrectionService corrections;
    private JournalEntry source;

    @BeforeEach
    void setUp() {
        transactions = transactions();
        corrections = corrections(transactions);
        source = sale(transactions, "DT001", 10_000_000);
    }

    @Test
    void adjustmentAddsOnlyItsDeltaAfterPostingAndPreservesTheSource() {
        VoucherDocument original = stored(source.getSourceDocumentId());
        VoucherDocument delta = corrections.createAdjustment(adjustment("DC001", 2_000_000), OWNER);
        assertEquals(VoucherStatus.DRAFT, delta.getStatus());
        assertEquals(Money.of(10_000_000), balance());
        postDraft(transactions, delta);
        assertEquals(Money.of(12_000_000), balance());
        assertEquals(original.getHistory(), stored(original.getId()).getHistory());
        assertEquals(VoucherSnapshot.from(original.getContent()), VoucherSnapshot.from(stored(original.getId()).getContent()));
        assertEquals(CorrectionKind.ADJUSTMENT, corrections.history(VIEWER).getFirst().kind());
    }

    @Test
    void reversalIsLinkedAndClosesTheOriginalDebtWithoutChangingThePostedSource() {
        VoucherDocument original = stored(source.getSourceDocumentId());
        ReversalRequest request = reversal(source.getSourceDocumentId(), "DAO001");
        VoucherDocument draft = corrections.createReversal(request, OWNER);
        assertEquals(Money.of(10_000_000), balance());
        assertEquals(Money.of(10_000_000), draft.getContent().getLines().getFirst().getCredit());
        assertEquals("KH01", draft.getContent().getLines().getFirst().getPartnerCode().orElseThrow());
        JournalEntry posted = postDraft(transactions, draft);
        assertEquals(Money.ZERO, balance());
        assertEquals(Money.ZERO, new LedgerService(transactions).netBalance("5113", DATE, VIEWER));
        assertTrue(new DebtService(transactions).statement("131", "KH01", DATE, VIEWER).openItems().isEmpty());
        assertEquals(original.getHistory(), stored(original.getId()).getHistory());
        assertEquals(1, new DebtService(transactions).allocations(VIEWER).size());
        assertEquals(posted.getId(), posting(transactions).post(draft.getId(), draft.getVersion() + 2, CHIEF).getId());
        assertEquals(draft.getId(), corrections.createReversal(request, OWNER).getId());
    }

    @Test
    void partiallyCollectedInvoiceRequiresUnmatchingBeforeReversalAndLeavesAnAdvance() {
        JournalEntry collected = receipt(transactions, "PT001", 4_000_000);
        var allocation = allocations(transactions).allocate(request(source, collected, 4_000_000), CHIEF);
        VoucherDocument reversal = approve(transactions, corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO001"), OWNER));
        assertThrows(IllegalStateException.class, () -> posting(transactions).post(reversal.getId(), reversal.getVersion(), CHIEF));
        assertEquals(VoucherStatus.APPROVED, stored(reversal.getId()).getStatus());
        assertEquals(reversal.getHistory(), stored(reversal.getId()).getHistory());
        assertEquals(2, new LedgerService(transactions).entries(VIEWER).size());
        allocations(transactions).cancel(allocation.id(), DATE, "Unmatch before voiding the invoice", CHIEF);
        posting(transactions).post(reversal.getId(), reversal.getVersion(), CHIEF);
        var statement = new DebtService(transactions).statement("131", "KH01", DATE, VIEWER);
        assertEquals(Money.ZERO, statement.unsettledDebt());
        assertEquals(Money.of(4_000_000), statement.unallocatedSettlements());
        assertEquals(Money.of(-4_000_000), statement.netDebt());
        assertEquals(Money.of(4_000_000), new LedgerService(transactions).netBalance("1111", DATE, VIEWER));
        var system = new DebtService(transactions).allocations(VIEWER).stream()
                .filter(value -> !value.id().equals(allocation.id())).findFirst().orElseThrow();
        assertThrows(IllegalStateException.class, () -> allocations(transactions).cancel(system.id(), DATE, "Cannot unlink a reversal", CHIEF));
        assertEquals(1, new DebtService(transactions).cancellations(VIEWER).size());
    }

    @Test
    void reversingAReceiptRestoresTheInvoiceAndPreservesItsAnalyticDetails() {
        JournalEntry collected = receipt(transactions, "PT001", 4_000_000);
        var allocation = allocations(transactions).allocate(request(source, collected, 4_000_000), CHIEF);
        allocations(transactions).cancel(allocation.id(), DATE, "Void the incorrect collection", CHIEF);
        VoucherDocument draft = corrections.createReversal(reversal(collected.getSourceDocumentId(), "DAO_PT"), OWNER);
        assertEquals("CASH01", draft.getContent().getLines().getFirst().getMoneyAccountCode().orElseThrow());
        postDraft(transactions, draft);
        assertEquals(Money.of(10_000_000), balance());
        assertEquals(Money.ZERO, new LedgerService(transactions).netBalance("1111", DATE, VIEWER));
        assertEquals(Money.of(10_000_000), new DebtService(transactions).statement("131", "KH01", DATE, VIEWER).unsettledDebt());
    }

    @Test
    void aSourceCannotHaveTwoLiveReversalsButCancelledDraftCanBeReplaced() {
        VoucherDocument draft = corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO001"), OWNER);
        assertThrows(IllegalStateException.class, () -> corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO002"), OWNER));
        transactions.execute(session -> workflow(session).cancel(draft.getId(), 0, OWNER, "Wrong date"));
        VoucherDocument replacement = corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO002"), OWNER);
        postDraft(transactions, replacement);
        assertThrows(IllegalStateException.class, () -> corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO003"), OWNER));
        assertEquals(2, corrections.history(VIEWER).size());
    }

    @Test
    void pendingAndPostedAdjustmentsMustBeResolvedBeforeVoidingTheSource() {
        VoucherDocument delta = corrections.createAdjustment(adjustment("DC001", 2_000_000), OWNER);
        assertThrows(IllegalStateException.class, () -> corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO001"), OWNER));
        postDraft(transactions, delta);
        assertThrows(IllegalStateException.class, () -> corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO001"), OWNER));
        postDraft(transactions, corrections.createReversal(reversal(delta.getId(), "DAO_DC"), OWNER));
        postDraft(transactions, corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO001"), OWNER));
        assertEquals(Money.ZERO, balance());
        assertEquals(Money.ZERO, new LedgerService(transactions).netBalance("5113", DATE, VIEWER));
        assertTrue(new DebtService(transactions).statement("131", "KH01", DATE, VIEWER).openItems().isEmpty());
    }

    @Test
    void generatedReversalContentCannotBeEditedAndAnAdjustmentRetryPreservesItsLaterEdits() {
        VoucherDocument draft = corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO001"), OWNER);
        Draft changed = new Draft("DAO001", DATE, DATE, "Changed", List.of(
                debit("5113", 1, null, null), credit("131", 1, "KH01", null)));
        assertThrows(IllegalArgumentException.class, () -> transactions.execute(session ->
                workflow(session).edit(draft.getId(), 0, new GeneralCreator(), changed, OWNER)));
        assertEquals(draft.getHistory(), stored(draft.getId()).getHistory());
        transactions.execute(session -> workflow(session).cancel(draft.getId(), 0, OWNER, "Create an adjustment instead"));
        AdjustmentRequest request = adjustment("DC001", 2_000_000);
        VoucherDocument adjustment = corrections.createAdjustment(request, OWNER);
        Draft newContent = new Draft("DC001", DATE, DATE, "Revised delta", List.of(
                debit("131", 3_000_000, "KH01", null), credit("5113", 3_000_000, null, null)));
        transactions.execute(session -> workflow(session).edit(adjustment.getId(), 0, new GeneralCreator(), newContent, OWNER));
        assertEquals(Money.of(3_000_000), corrections.createAdjustment(request, OWNER).getContent().getTotalDebit());
        assertThrows(IllegalArgumentException.class, () -> corrections.createAdjustment(new AdjustmentRequest(request.id(),
                request.sourceDocumentId(), "Different reason", request.content()), OWNER));
    }

    @ParameterizedTest
    @ValueSource(longs = {CHIEF, VIEWER, ADMIN, 999})
    void permissionsProtectCorrectionCreation(long actorId) {
        assertThrows(AccessDeniedException.class, () -> corrections.createAdjustment(adjustment("DC001", 1), actorId));
        assertThrows(AccessDeniedException.class, () -> corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO001"), actorId));
        assertTrue(corrections.history(VIEWER).isEmpty());
    }

    @Test
    void rejectsUnpostedSourcesDatesBeforeTheSourceAndClosedPeriods() {
        VoucherDocument unposted = transactions.execute(session -> workflow(session).create(new GeneralCreator(),
                new Draft("DRAFT", DATE, DATE, "Unposted", List.of(debit("131", 1, "KH01", null), credit("5113", 1, null, null))), OWNER));
        assertThrows(IllegalArgumentException.class, () -> corrections.createReversal(reversal(unposted.getId(), "DAO001"), OWNER));
        assertThrows(IllegalArgumentException.class, () -> corrections.createReversal(new ReversalRequest(UUID.randomUUID(),
                source.getSourceDocumentId(), "DAO002", DATE.minusDays(1), "Too early"), OWNER));
        transactions.execute(session -> {
            session.periods().update(session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow().beginClosing().completeClosing());
            return null;
        });
        assertThrows(IllegalStateException.class, () -> corrections.createAdjustment(adjustment("DC001", 1), OWNER));
        assertTrue(corrections.history(VIEWER).isEmpty());
    }

    @Test
    void downstreamPolicyFailureRollsBackReversalMatchingAndThePostingTogether() {
        VoucherDocument approved = approve(transactions, corrections.createReversal(reversal(source.getSourceDocumentId(), "DAO001"), OWNER));
        List<String> called = new ArrayList<>();
        PostingPolicy failure = new PostingPolicy() {
            @Override public void beforePost(vn.ketoan.application.port.AccountingSession session, VoucherDocument document) { called.add("before"); }
            @Override public void afterPost(vn.ketoan.application.port.AccountingSession session, JournalEntry entry) {
                called.add("after");
                assertEquals(1, session.allocations().findAll().size());
                throw new IllegalStateException("Failure after automatic matching");
            }
        };
        PostingService posting = new PostingService(transactions, validators(), CLOCK,
                List.of(new CorrectionPostingPolicy(), new PrepaidPostingPolicy(), failure));
        assertThrows(IllegalStateException.class, () -> posting.post(approved.getId(), approved.getVersion(), CHIEF));
        assertEquals(List.of("before", "after"), called);
        assertEquals(1, new LedgerService(transactions).entries(VIEWER).size());
        assertTrue(new DebtService(transactions).allocations(VIEWER).isEmpty());
        assertEquals(approved.getHistory(), stored(approved.getId()).getHistory());
        posting(transactions).post(approved.getId(), approved.getVersion(), CHIEF);
        assertEquals(Money.ZERO, balance());
    }

    private ReversalRequest reversal(UUID sourceId, String reference) {
        return new ReversalRequest(UUID.randomUUID(), sourceId, reference, DATE, "Void the incorrect voucher");
    }

    private AdjustmentRequest adjustment(String reference, long amount) {
        return new AdjustmentRequest(UUID.randomUUID(), source.getSourceDocumentId(), "Increase the service value",
                new Draft(reference, DATE, DATE, "Service value delta", List.of(
                        debit("131", amount, "KH01", null), credit("5113", amount, null, null))));
    }

    private Money balance() { return new LedgerService(transactions).partnerNetBalance("131", "KH01", DATE, VIEWER); }
    private VoucherDocument stored(UUID id) { return transactions.execute(session -> session.documents().findById(id).orElseThrow()); }
}
