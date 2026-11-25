package vn.ketoan.infrastructure.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.application.service.DebtService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.application.service.PostingService;
import vn.ketoan.application.service.RecognitionService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.demo.JdbcDemo;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.PeriodStatus;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherSnapshot;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.SettlementTestData.allocations;
import static vn.ketoan.SettlementTestData.postDraft;
import static vn.ketoan.SettlementTestData.receipt;
import static vn.ketoan.SettlementTestData.request;
import static vn.ketoan.SettlementTestData.sale;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.VoucherTestData.account;
import static vn.ketoan.VoucherTestData.credit;
import static vn.ketoan.VoucherTestData.debit;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class JdbcStorageTest {

    private JdbcTestDatabase database;
    private UnitOfWork transactions;

    @BeforeEach
    void setUp() throws Exception {
        database = new JdbcTestDatabase();
        database.migrateAndSeed();
        transactions = database.transactions();
    }

    @AfterEach
    void tearDown() throws Exception {
        database.close();
    }

    @Test
    void independentUnitOfWorkInstancesReadPersistedPostingAllocationAndCancellation() {
        JournalEntry sale = sale(transactions, "DT001", 10_000_000);
        JournalEntry receipt = receipt(transactions, "PT001", 4_000_000);
        var allocation = allocations(transactions).allocate(request(sale, receipt, 4_000_000), CHIEF);
        UnitOfWork reconnected = database.transactions();
        DebtService debts = new DebtService(reconnected);
        assertEquals(Money.of(6_000_000), debts.statement("131", "KH01", DATE, VIEWER).netDebt());
        assertEquals(Money.of(6_000_000), debts.statement("131", "KH01", DATE, VIEWER).unsettledDebt());
        assertEquals(Money.of(4_000_000), new LedgerService(reconnected).netBalance("1111", DATE, VIEWER));
        assertEquals(2, new LedgerService(reconnected).entries(VIEWER).size());
        assertEquals(allocation, debts.allocations(VIEWER).getFirst());
        var cancellation = allocations(reconnected).cancel(allocation.id(), DATE.plusDays(1), "Wrong invoice", CHIEF);
        DebtService reopened = new DebtService(database.transactions());
        assertEquals(cancellation, reopened.cancellations(VIEWER).getFirst());
        assertEquals(Money.of(6_000_000), reopened.statement("131", "KH01", DATE, VIEWER).unsettledDebt());
        assertEquals(Money.of(10_000_000), reopened.statement("131", "KH01", DATE.plusDays(1), VIEWER).unsettledDebt());
        assertEquals(Money.of(6_000_000), reopened.statement("131", "KH01", DATE.plusDays(1), VIEWER).netDebt());
    }

    @Test
    void catalogsRolesAndPeriodStatesRoundTripWithoutLosingTheirMeaning() {
        transactions.execute(session -> {
            session.accounts().insert(account("5118", AccountType.REVENUE, AccountTracking.NONE).deactivate());
            session.partners().insert(new Partner("BOTH", "Đối tượng Nguyễn 😀", Set.of(PartnerRole.CUSTOMER, PartnerRole.SUPPLIER)).deactivate());
            session.moneyAccounts().update(session.moneyAccounts().findByCode("BANK01").orElseThrow().deactivate());
            session.users().update(new User(OWNER, "renamed", "Nguyễn Văn A", Set.of(UserRole.ACCOUNTANT, UserRole.CHIEF)));
            session.periods().update(session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow().beginClosing().completeClosing());
            return null;
        });
        database.transactions().execute(session -> {
            assertFalse(session.accounts().findByCode("5118").orElseThrow().isActive());
            assertFalse(session.accounts().findByCode("111").orElseThrow().isPostingAllowed());
            assertEquals(Set.of(PartnerRole.CUSTOMER, PartnerRole.SUPPLIER), session.partners().findByCode("BOTH").orElseThrow().getRoles());
            assertEquals("Đối tượng Nguyễn 😀", session.partners().findByCode("BOTH").orElseThrow().getName());
            assertFalse(session.moneyAccounts().findByCode("BANK01").orElseThrow().isActive());
            assertEquals(Set.of(UserRole.ACCOUNTANT, UserRole.CHIEF), session.users().findById(OWNER).orElseThrow().getRoles());
            assertEquals("renamed", session.users().findById(OWNER).orElseThrow().getUsername());
            assertEquals(PeriodStatus.CLOSED, session.periods().findByMonth(YearMonth.from(DATE)).orElseThrow().getStatus());
            return null;
        });
    }

    @Test
    void incompleteDraftsKeepHistoricalSnapshotsAfterTheyAreCorrectedAndPosted() {
        Draft input = new Draft("GENERAL", DATE, DATE, "Incomplete entry", List.of(debit("UNKNOWN", 10, null, null)));
        VoucherDocument original = transactions.execute(session -> workflow(session).create(new GeneralCreator(), input, OWNER));
        VoucherDocument edited = transactions.execute(session -> workflow(session).edit(original.getId(), 0,
                new GeneralCreator(), input.withLines(List.of(debit("1111", 10, null, "CASH01"), credit("4111", 10, null, null))), OWNER));
        postDraft(transactions, edited);
        VoucherDocument stored = database.transactions().execute(session -> session.documents().findById(original.getId()).orElseThrow());
        assertEquals(VoucherStatus.POSTED, stored.getStatus());
        assertEquals(5, stored.getHistory().size());
        assertEquals("UNKNOWN", stored.getHistory().getFirst().content().lines().getFirst().accountCode());
        assertEquals("1111", stored.getContent().getLines().getFirst().getAccountCode());
        assertEquals(original.getHistory().getFirst(), stored.getHistory().getFirst());
    }

    @Test
    void wholeVndAmountsAtDecimalLimitAndSqlLookingTextAreStoredExactly() {
        Money maximum = Money.of("9999999999999999999");
        String description = "Dịch vụ 'quoted'; DROP TABLE acc_accounts; -- 😀";
        RecognitionDraft input = new RecognitionDraft("DT001", DATE, DATE, description, "KH01", "131",
                List.of(new RecognitionItem("5113", maximum)));
        VoucherDocument draft = new RecognitionService(transactions, validators(), CLOCK).createRevenue(input, OWNER);
        postDraft(transactions, draft);
        JournalEntry stored = new LedgerService(database.transactions()).entries(VIEWER).getFirst();
        assertEquals(maximum, stored.getTotalDebit());
        assertEquals(description, stored.getContent().description());
        assertEquals(maximum, new LedgerService(database.transactions()).partnerNetBalance("131", "KH01", DATE, VIEWER));
        assertTrue(database.transactions().<Boolean>execute(session -> session.accounts().findByCode("131").isPresent()));
    }

    @Test
    void nanosecondAuditTimesSurviveReloadAndChangedUserRolesDoNotRewriteHistory() {
        Instant instant = Instant.parse("2026-11-24T03:00:00.123456789Z");
        Clock clock = Clock.fixed(instant, ZoneOffset.UTC);
        VoucherDocument draft = new RecognitionService(transactions, validators(), clock).createRevenue(new RecognitionDraft(
                "DT001", DATE, DATE, "Precise timestamp", "KH01", "131",
                List.of(new RecognitionItem("5113", Money.of(10)))), OWNER);
        VoucherDocument approved = transactions.execute(session -> {
            VoucherWorkflowService workflow = new VoucherWorkflowService(session.users(), session.documents(), validators().create(session), clock);
            VoucherDocument pending = workflow.submit(draft.getId(), 0, OWNER);
            return workflow.approve(pending.getId(), pending.getVersion(), CHIEF);
        });
        new PostingService(transactions, validators(), clock).post(approved.getId(), approved.getVersion(), CHIEF);
        transactions.execute(session -> {
            session.users().update(new User(CHIEF, "formerchief", "Former chief", Set.of(UserRole.VIEWER)).deactivate());
            return null;
        });
        VoucherDocument stored = database.transactions().execute(session -> session.documents().findById(draft.getId()).orElseThrow());
        assertEquals(instant, stored.getCreatedAt());
        assertEquals(instant, stored.getPostedAt().orElseThrow());
        assertEquals(instant, stored.getHistory().getLast().occurredAt());
        assertEquals(instant, new LedgerService(database.transactions()).entries(VIEWER).getFirst().getPostedAt());
        assertEquals(VoucherStatus.POSTED, stored.getStatus());
    }

    @Test
    void compareAndSaveRejectsStaleVersionAndFailedEditLeavesAllTablesUnchanged() {
        RecognitionService recognition = new RecognitionService(transactions, validators(), CLOCK);
        RecognitionDraft input = new RecognitionDraft("DT001", DATE, DATE, "Original", "KH01", "131",
                List.of(new RecognitionItem("5113", Money.of(10))));
        VoucherDocument original = recognition.createRevenue(input, OWNER);
        RecognitionDraft replacement = new RecognitionDraft("DT001", DATE, DATE, "Revised", "KH01", "131",
                List.of(new RecognitionItem("5113", Money.of(20))));
        VoucherDocument edited = recognition.editRevenue(original.getId(), 0, replacement, OWNER);
        assertThrows(StaleVersionException.class, () -> recognition.editRevenue(original.getId(), 0, input, OWNER));
        RecognitionDraft invalid = new RecognitionDraft("DT001", DATE, DATE, "Invalid", "NCC01", "131",
                List.of(new RecognitionItem("5113", Money.of(30))));
        assertThrows(IllegalStateException.class, () -> recognition.editRevenue(original.getId(), 1, invalid, OWNER));
        VoucherDocument stored = database.transactions().execute(session -> session.documents().findById(original.getId()).orElseThrow());
        assertEquals(edited.getHistory(), stored.getHistory());
        assertEquals(VoucherSnapshot.from(edited.getContent()), VoucherSnapshot.from(stored.getContent()));
        assertEquals(1, stored.getVersion());
    }

    @Test
    void rejectsStoredContentWhichNoLongerMatchesItsAuditOrPostedSource() throws Exception {
        RecognitionDraft input = new RecognitionDraft("DT001", DATE, DATE, "Audit consistency", "KH01", "131",
                List.of(new RecognitionItem("5113", Money.of(10))));
        VoucherDocument draft = new RecognitionService(transactions, validators(), CLOCK).createRevenue(input, OWNER);
        database.execute("UPDATE acc_voucher_lines SET amount=20");
        assertThrows(IllegalStateException.class, () -> database.transactions().execute(session ->
                session.documents().findById(draft.getId()).orElseThrow()));
        database.execute("UPDATE acc_voucher_lines SET amount=10");
        postDraft(transactions, draft);
        database.execute("UPDATE acc_journal_lines SET amount=20");
        assertThrows(IllegalStateException.class, () -> new LedgerService(database.transactions()).entries(VIEWER));
    }

    @Test
    void persistentDemoCanBeRepeatedWithoutCreatingDuplicateRecords() throws Exception {
        // The demo owns a separate empty database, so it never mixes sample identities with other fixtures.
        try (JdbcTestDatabase sample = new JdbcTestDatabase()) {
            new SchemaMigrator(sample.connections()).migrate();
            JdbcDemo.seedCatalogs(sample.transactions());
            PrintStream output = new PrintStream(new ByteArrayOutputStream());
            JdbcDemo.run(output, sample.transactions(), CLOCK);
            JdbcDemo.run(output, sample.transactions(), CLOCK);
            assertEquals(2, new LedgerService(sample.transactions()).entries(VIEWER).size());
            assertEquals(1, new DebtService(sample.transactions()).allocations(VIEWER).size());
            assertEquals(Money.of(6_000_000), new DebtService(sample.transactions())
                    .statement("131", "KH01", java.time.LocalDate.of(2026, 11, 25), VIEWER).netDebt());
            assertThrows(IllegalStateException.class, () -> JdbcDemo.seedCatalogs(sample.transactions()));
        }
    }
}
