package vn.ketoan.application.service;

import java.time.YearMonth;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.application.port.SettlementUseCases;
import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.SettlementDraft;
import vn.ketoan.domain.VoucherAction;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.VoucherType;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.SettlementTestData.receiptInput;
import static vn.ketoan.SettlementTestData.settlements;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OTHER;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class SettlementServiceTest {

    private InMemoryUnitOfWork transactions;
    private SettlementUseCases settlements;

    @BeforeEach
    void setUp() {
        transactions = transactions();
        settlements = settlements(transactions);
    }

    @ParameterizedTest
    @CsvSource({"true,CASH01,1111", "true,BANK01,1121", "false,CASH01,1111", "false,BANK01,1121"})
    void createsBalancedDraftAndResolvesLedgerFromSelectedFund(boolean customer, String fund, String ledgerCode) {
        VoucherDocument draft = create(customer, input(customer, "SET001", fund, 4_000_000));
        VoucherLine money = draft.getContent().getLines().stream()
                .filter(line -> line.getMoneyAccountCode().isPresent()).findFirst().orElseThrow();

        assertEquals(customer ? VoucherType.RECEIPT : VoucherType.PAYMENT, draft.getContent().getType());
        assertEquals(VoucherStatus.DRAFT, draft.getStatus());
        assertEquals(Money.of(4_000_000), draft.getContent().getTotalDebit());
        assertEquals(draft.getContent().getTotalDebit(), draft.getContent().getTotalCredit());
        assertEquals(ledgerCode, money.getAccountCode());
        assertEquals(fund, money.getMoneyAccountCode().orElseThrow());
        assertEquals(customer ? Money.of(4_000_000) : Money.ZERO, money.getDebit());
        assertEquals(customer ? Money.ZERO : Money.of(4_000_000), money.getCredit());
        assertEquals(OWNER, draft.getCreatedBy());
        assertEquals(VoucherAction.CREATED, draft.getHistory().getFirst().action());
        assertTrue(new LedgerService(transactions).entries(VIEWER).isEmpty());
        assertTrue(new DebtService(transactions).allocations(VIEWER).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void editsDraftAndRejectsStaleVersionsOtherOwnersAndSubmittedContent(boolean customer) {
        VoucherDocument original = create(customer, input(customer, "SET001", "CASH01", 4));
        SettlementDraft replacement = input(customer, "SET001", "BANK01", 5);
        assertThrows(AccessDeniedException.class, () -> edit(customer, original, replacement, OTHER));
        VoucherDocument edited = edit(customer, original, replacement, OWNER);
        assertEquals(1, edited.getVersion());
        assertEquals(Money.of(5), edited.getContent().getTotalDebit());
        assertEquals(original.getHistory().getFirst(), edited.getHistory().getFirst());
        assertThrows(StaleVersionException.class, () -> edit(customer, original, replacement, OWNER));
        VoucherDocument pending = transactions.execute(session ->
                workflow(session).submit(edited.getId(), edited.getVersion(), OWNER));
        assertThrows(IllegalStateException.class, () -> edit(customer, pending, replacement, OWNER));
        assertEquals(pending, stored(original));
    }

    @Test
    void invalidEditPreservesPreviousDocumentAndAudit() {
        VoucherDocument original = settlements.createCustomerReceipt(receiptInput("PT001", 4), OWNER);
        transactions.execute(session -> {
            session.moneyAccounts().update(session.moneyAccounts().findByCode("BANK01").orElseThrow().deactivate());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> settlements.editCustomerReceipt(original.getId(), 0, input(true, "PT001", "BANK01", 5), OWNER));
        assertEquals(original, stored(original));
    }

    @ParameterizedTest
    @ValueSource(longs = {CHIEF, VIEWER, ADMIN, 999})
    void onlyAuthorizedActiveAccountantsCanCreateSettlementDrafts(long actorId) {
        assertThrows(AccessDeniedException.class,
                () -> settlements.createCustomerReceipt(receiptInput("PT001", 4), actorId));
        assertThrows(AccessDeniedException.class,
                () -> settlements.createSupplierPayment(input(false, "PC001", "CASH01", 1), actorId));
        assertEquals(0, count());
    }

    @Test
    void rejectsWrongPartnerRoleAndDebtType() {
        assertThrows(IllegalStateException.class, () -> settlements.createCustomerReceipt(
                new SettlementDraft("BAD1", DATE, DATE, "Wrong role", "NCC01", "131", "CASH01", Money.of(1)), OWNER));
        assertThrows(IllegalStateException.class, () -> settlements.createSupplierPayment(
                new SettlementDraft("BAD2", DATE, DATE, "Wrong role", "KH01", "331", "CASH01", Money.of(1)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> settlements.createCustomerReceipt(
                new SettlementDraft("BAD3", DATE, DATE, "Wrong account", "KH01", "331", "CASH01", Money.of(1)), OWNER));
        assertThrows(IllegalArgumentException.class, () -> settlements.createSupplierPayment(
                new SettlementDraft("BAD4", DATE, DATE, "Wrong account", "NCC01", "6427", "CASH01", Money.of(1)), OWNER));
        assertEquals(0, count());
    }

    @Test
    void rejectsMissingOrInactiveFundLedgerPartnerAndClosedPeriod() {
        assertThrows(IllegalArgumentException.class,
                () -> settlements.createCustomerReceipt(input(true, "PT001", "MISSING", 4), OWNER));
        transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("1111").orElseThrow().deactivate());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> settlements.createCustomerReceipt(receiptInput("PT001", 4), OWNER));
        transactions.execute(session -> {
            session.accounts().update(session.accounts().findByCode("1111").orElseThrow().activate());
            session.partners().update(session.partners().findByCode("KH01").orElseThrow().deactivate());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> settlements.createCustomerReceipt(receiptInput("PT001", 4), OWNER));
        transactions.execute(session -> {
            session.partners().update(session.partners().findByCode("KH01").orElseThrow().activate());
            session.periods().update(session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow().beginClosing());
            return null;
        });
        assertThrows(IllegalStateException.class,
                () -> settlements.createCustomerReceipt(receiptInput("PT001", 4), OWNER));
        assertEquals(0, count());
    }

    @Test
    void inactiveCreatorCannotSaveAndReferencesRemainUnique() {
        VoucherDocument original = settlements.createCustomerReceipt(receiptInput("SET001", 4), OWNER);
        assertThrows(IllegalStateException.class,
                () -> settlements.createSupplierPayment(input(false, "set001", "CASH01", 1), OWNER));
        transactions.execute(session -> {
            session.users().update(session.users().findById(OWNER).orElseThrow().deactivate());
            return null;
        });
        assertThrows(AccessDeniedException.class,
                () -> settlements.createCustomerReceipt(receiptInput("PT002", 4), OWNER));
        assertEquals(original, stored(original));
        assertEquals(1, count());
    }

    private SettlementDraft input(boolean customer, String reference, String fund, long amount) {
        return new SettlementDraft(reference, DATE, DATE, "Settlement draft", customer ? "KH01" : "NCC01",
                customer ? "131" : "331", fund, Money.of(amount));
    }

    private VoucherDocument create(boolean customer, SettlementDraft input) {
        return customer ? settlements.createCustomerReceipt(input, OWNER) : settlements.createSupplierPayment(input, OWNER);
    }

    private VoucherDocument edit(boolean customer, VoucherDocument doc, SettlementDraft input, long actorId) {
        return customer ? settlements.editCustomerReceipt(doc.getId(), doc.getVersion(), input, actorId)
                : settlements.editSupplierPayment(doc.getId(), doc.getVersion(), input, actorId);
    }

    private VoucherDocument stored(VoucherDocument document) {
        return transactions.execute(session -> session.documents().findById(document.getId()).orElseThrow());
    }

    private int count() {
        return transactions.execute(session -> session.documents().findAll().size());
    }
}
