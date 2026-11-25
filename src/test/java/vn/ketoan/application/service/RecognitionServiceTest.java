package vn.ketoan.application.service;

import java.time.YearMonth;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.application.port.RecognitionUseCases;
import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.VoucherAction;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.VoucherType;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.account;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OTHER;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.VIEWER;

class RecognitionServiceTest {

    private InMemoryUnitOfWork transactions;
    private RecognitionUseCases recognition;

    @BeforeEach
    void setUp() {
        transactions = transactions();
        recognition = new RecognitionService(transactions, validators(), CLOCK);
    }

    @ParameterizedTest
    @EnumSource(value = VoucherType.class, names = {"REVENUE", "EXPENSE"})
    void createsValidatedDraftWithOwnerAndAuditWithoutPosting(VoucherType type) {
        VoucherDocument draft = create(type, validInput(type, "REC001", 10));

        assertEquals(type, draft.getContent().getType());
        assertEquals(VoucherStatus.DRAFT, draft.getStatus());
        assertEquals(OWNER, draft.getCreatedBy());
        assertEquals(0, draft.getVersion());
        assertEquals(TIME, draft.getCreatedAt());
        assertEquals(Money.of(10), draft.getContent().getTotalDebit());
        assertEquals(draft.getContent().getTotalDebit(), draft.getContent().getTotalCredit());
        assertEquals(1, draft.getHistory().size());
        assertEquals(VoucherAction.CREATED, draft.getHistory().getFirst().action());
        assertEquals(draft, stored(draft));
        assertTrue(new LedgerService(transactions).entries(VIEWER).isEmpty());
    }

    @Test
    void supportsMultipleBusinessComponentsAndSeparateTaxAmounts() {
        transactions.execute(session -> {
            session.accounts().insert(account("5118", AccountType.REVENUE, AccountTracking.NONE));
            session.accounts().insert(account("6428", AccountType.EXPENSE, AccountTracking.NONE));
            return null;
        });
        VoucherDocument sale = recognition.createRevenue(input("DT001", "KH01", "131",
                item("5113", 6_000_000), item("5118", 4_000_000), item("33311", 1_000_000)), OWNER);
        VoucherDocument cost = recognition.createExpense(input("CP001", "NCC01", "331",
                item("6427", 2_000_000), item("6428", 1_000_000), item("1331", 300_000)), OWNER);

        assertEquals(4, sale.getContent().getLines().size());
        assertEquals(Money.of(11_000_000), sale.getContent().getLines().getFirst().getDebit());
        assertEquals("KH01", sale.getContent().getLines().getFirst().getPartnerCode().orElseThrow());
        assertEquals(4, cost.getContent().getLines().size());
        assertEquals(Money.of(3_300_000), cost.getContent().getLines().getLast().getCredit());
        assertEquals("NCC01", cost.getContent().getLines().getLast().getPartnerCode().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(longs = {CHIEF, VIEWER, ADMIN, 999})
    void requiresCreationPermissionForBothUseCases(long actorId) {
        assertThrows(AccessDeniedException.class,
                () -> recognition.createRevenue(validInput(VoucherType.REVENUE, "DT001", 10), actorId));
        assertThrows(AccessDeniedException.class,
                () -> recognition.createExpense(validInput(VoucherType.EXPENSE, "CP001", 10), actorId));
        assertEquals(0, documentCount());
    }

    @Test
    void rejectsInactiveCreatorAndInactiveCounterpartyWithoutSavingAnything() {
        transactions.execute(session -> {
            session.users().update(session.users().findById(OWNER).orElseThrow().deactivate());
            return null;
        });
        assertThrows(AccessDeniedException.class,
                () -> recognition.createRevenue(validInput(VoucherType.REVENUE, "DT001", 10), OWNER));
        transactions.execute(session -> {
            session.users().update(session.users().findById(OWNER).orElseThrow().activate());
            session.partners().update(session.partners().findByCode("KH01").orElseThrow().deactivate());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> recognition.createRevenue(validInput(VoucherType.REVENUE, "DT001", 10), OWNER));
        assertEquals(0, documentCount());
    }

    @Test
    void requiresCustomerForRevenueAndSupplierForExpense() {
        assertThrows(IllegalStateException.class, () -> recognition.createRevenue(
                input("DT001", "NCC01", "131", item("5113", 10)), OWNER));
        assertThrows(IllegalStateException.class, () -> recognition.createExpense(
                input("CP001", "KH01", "331", item("6427", 10)), OWNER));
        assertEquals(0, documentCount());
    }

    @Test
    void rejectsWrongDebtTypesMoneyMovementsAndMissingMainRecognition() {
        assertThrows(IllegalArgumentException.class, () -> recognition.createRevenue(
                input("DT001", "KH01", "331", item("5113", 10)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> recognition.createExpense(
                input("CP001", "NCC01", "131", item("6427", 10)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> recognition.createRevenue(
                input("DT002", "KH01", "1111", item("5113", 10)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> recognition.createExpense(
                input("CP002", "NCC01", "331", item("1111", 10)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> recognition.createRevenue(
                input("DT003", "KH01", "131", item("33311", 10)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> recognition.createExpense(
                input("CP003", "NCC01", "331", item("1331", 10)), OWNER));
        assertEquals(0, documentCount());
    }

    @Test
    void rejectsMissingCatalogReferencesWithoutLeavingDraftOrHistory() {
        assertThrows(IllegalArgumentException.class, () -> recognition.createRevenue(
                input("DT001", "UNKNOWN", "131", item("5113", 10)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> recognition.createRevenue(
                input("DT002", "KH01", "131", item("MISSING", 10)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> recognition.createExpense(
                new RecognitionDraft("CP001", DATE, DATE.plusMonths(1), "Missing period",
                        "NCC01", "331", List.of(item("6427", 10))), OWNER));
        assertEquals(0, documentCount());
    }

    @Test
    void requiresActivePostingAccountAndOpenPeriodAtEntry() {
        transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("5113").orElseThrow().deactivate());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> recognition.createRevenue(validInput(VoucherType.REVENUE, "DT001", 10), OWNER));
        assertThrows(IllegalStateException.class, () -> recognition.createRevenue(
                input("DT002", "KH01", "131", item("111", 10)), OWNER));
        transactions.execute(session -> {
            session.periods().update(session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow().beginClosing());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> recognition.createExpense(validInput(VoucherType.EXPENSE, "CP001", 10), OWNER));
        assertEquals(0, documentCount());
    }

    @Test
    void enforcesUniqueReferenceAcrossRecognitionTypes() {
        VoucherDocument original = create(VoucherType.REVENUE, validInput(VoucherType.REVENUE, "SAME", 10));
        assertThrows(IllegalStateException.class,
                () -> create(VoucherType.EXPENSE, validInput(VoucherType.EXPENSE, "same", 20)));
        assertEquals(1, documentCount());
        assertEquals(original, stored(original));
    }

    @ParameterizedTest
    @EnumSource(value = VoucherType.class, names = {"REVENUE", "EXPENSE"})
    void editsDraftWithNewVersionAndPreservesOriginalAuditSnapshot(VoucherType type) {
        VoucherDocument original = create(type, validInput(type, "REC001", 10));
        VoucherDocument edited = edit(type, original, validInput(type, "REC001", 20), OWNER);

        assertEquals(original.getId(), edited.getId());
        assertEquals(VoucherStatus.DRAFT, edited.getStatus());
        assertEquals(1, edited.getVersion());
        assertEquals(Money.of(20), edited.getContent().getTotalDebit());
        assertEquals(2, edited.getHistory().size());
        assertEquals(VoucherAction.EDITED, edited.getHistory().getLast().action());
        assertEquals(original.getHistory().getFirst(), edited.getHistory().getFirst());
        assertEquals(Money.of(10), original.getContent().getTotalDebit());
        assertEquals(edited, stored(original));
    }

    @ParameterizedTest
    @EnumSource(value = VoucherType.class, names = {"REVENUE", "EXPENSE"})
    void invalidEditRollsBackContentVersionAndAudit(VoucherType type) {
        VoucherDocument original = create(type, validInput(type, "REC001", 10));
        RecognitionDraft invalid = type == VoucherType.REVENUE
                ? input("REC001", "KH01", "131", item("6427", 20))
                : input("REC001", "NCC01", "331", item("5113", 20));

        assertThrows(IllegalArgumentException.class, () -> edit(type, original, invalid, OWNER));
        assertEquals(original, stored(original));
        assertTrue(new LedgerService(transactions).entries(VIEWER).isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = VoucherType.class, names = {"REVENUE", "EXPENSE"})
    void editsRequireOwnerAndCurrentVersionAndKeepReferenceAndType(VoucherType type) {
        VoucherDocument original = create(type, validInput(type, "REC001", 10));
        RecognitionDraft replacement = validInput(type, "REC001", 20);
        assertThrows(AccessDeniedException.class, () -> edit(type, original, replacement, OTHER));
        assertThrows(IllegalArgumentException.class,
                () -> edit(type, original, validInput(type, "RENAMED", 20), OWNER));
        VoucherType otherType = type == VoucherType.REVENUE ? VoucherType.EXPENSE : VoucherType.REVENUE;
        assertThrows(IllegalArgumentException.class,
                () -> edit(otherType, original, validInput(otherType, "REC001", 20), OWNER));

        VoucherDocument edited = edit(type, original, replacement, OWNER);
        assertThrows(StaleVersionException.class, () -> edit(type, original, replacement, OWNER));
        assertEquals(edited, stored(original));
    }

    @Test
    void returnedDraftCanBeRevisedButPendingApprovedAndPostedDocumentsAreProtected() {
        VoucherDocument draft = create(VoucherType.REVENUE, validInput(VoucherType.REVENUE, "DT001", 10));
        VoucherDocument pending = transactions.execute(session -> workflow(session).submit(draft.getId(), 0, OWNER));
        RecognitionDraft replacement = validInput(VoucherType.REVENUE, "DT001", 20);
        assertThrows(IllegalStateException.class, () -> edit(VoucherType.REVENUE, pending, replacement, OWNER));

        VoucherDocument returned = transactions.execute(session -> workflow(session).returnForRevision(
                pending.getId(), pending.getVersion(), CHIEF, "Correct the service value"));
        VoucherDocument revised = edit(VoucherType.REVENUE, returned, replacement, OWNER);
        assertEquals(VoucherStatus.RETURNED, revised.getStatus());
        assertEquals(Money.of(20), revised.getContent().getTotalDebit());
        VoucherDocument approved = transactions.execute(session -> {
            VoucherWorkflowService workflow = workflow(session);
            VoucherDocument submitted = workflow.submit(revised.getId(), revised.getVersion(), OWNER);
            return workflow.approve(submitted.getId(), submitted.getVersion(), CHIEF);
        });
        assertThrows(IllegalStateException.class, () -> edit(VoucherType.REVENUE, approved, replacement, OWNER));
        posting(transactions).post(approved.getId(), approved.getVersion(), CHIEF);
        VoucherDocument posted = stored(approved);
        assertThrows(IllegalStateException.class, () -> edit(VoucherType.REVENUE, posted, replacement, OWNER));
        assertEquals(posted, stored(approved));
        assertEquals(1, new LedgerService(transactions).entries(VIEWER).size());
    }

    @Test
    void currentCatalogsAndPeriodAreRecheckedAtSubmissionApprovalAndPosting() {
        VoucherDocument draft = create(VoucherType.REVENUE, validInput(VoucherType.REVENUE, "DT001", 10));
        transactions.execute(session -> {
            session.partners().update(session.partners().findByCode("KH01").orElseThrow().deactivate());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> transactions.execute(session -> workflow(session).submit(draft.getId(), 0, OWNER)));
        assertEquals(draft, stored(draft));

        VoucherDocument pending = transactions.execute(session -> {
            session.partners().update(session.partners().findByCode("KH01").orElseThrow().activate());
            return workflow(session).submit(draft.getId(), 0, OWNER);
        });
        transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("5113").orElseThrow().deactivate());
            return null;
        });
        assertThrows(IllegalStateException.class, () -> transactions.execute(session ->
                workflow(session).approve(pending.getId(), pending.getVersion(), CHIEF)));
        assertEquals(pending, stored(draft));

        VoucherDocument approved = transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("5113").orElseThrow().activate());
            return workflow(session).approve(pending.getId(), pending.getVersion(), CHIEF);
        });
        transactions.execute(session -> {
            session.periods().update(session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow().beginClosing());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> posting(transactions).post(approved.getId(), approved.getVersion(), CHIEF));
        assertEquals(approved, stored(draft));
        assertTrue(new LedgerService(transactions).entries(VIEWER).isEmpty());
    }

    private VoucherDocument create(VoucherType type, RecognitionDraft input) {
        return type == VoucherType.REVENUE
                ? recognition.createRevenue(input, OWNER) : recognition.createExpense(input, OWNER);
    }

    private VoucherDocument edit(VoucherType type, VoucherDocument original, RecognitionDraft input, long actorId) {
        return type == VoucherType.REVENUE
                ? recognition.editRevenue(original.getId(), original.getVersion(), input, actorId)
                : recognition.editExpense(original.getId(), original.getVersion(), input, actorId);
    }

    private RecognitionDraft validInput(VoucherType type, String reference, long amount) {
        return type == VoucherType.REVENUE
                ? input(reference, "KH01", "131", item("5113", amount))
                : input(reference, "NCC01", "331", item("6427", amount));
    }

    private RecognitionDraft input(String reference, String partner, String account, RecognitionItem... items) {
        return new RecognitionDraft(reference, DATE, DATE, "Recognized service", partner, account, List.of(items));
    }

    private RecognitionItem item(String account, long amount) {
        return new RecognitionItem(account, Money.of(amount));
    }

    private VoucherDocument stored(VoucherDocument document) {
        return transactions.execute(session -> session.documents().findById(document.getId()).orElseThrow());
    }

    private int documentCount() {
        return transactions.execute(session -> session.documents().findAll().size());
    }
}
