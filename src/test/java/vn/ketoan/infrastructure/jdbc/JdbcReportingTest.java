package vn.ketoan.infrastructure.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.ReportingService;
import vn.ketoan.demo.CorrectionDemo;
import vn.ketoan.demo.PrepaidDemo;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.report.BalanceSheetSection;
import vn.ketoan.domain.report.LedgerQuery;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.value.Money;
import static org.junit.jupiter.api.Assertions.*;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.ReportingTestData.*;
import static vn.ketoan.SettlementTestData.allocations;
import static vn.ketoan.SettlementTestData.request;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.VIEWER;

class JdbcReportingTest {
    private JdbcTestDatabase database;
    private UnitOfWork unit;
    @BeforeEach void setUp() throws Exception {
        database = new JdbcTestDatabase();
        database.migrateAndSeed();
        unit = database.transactions();
        seedBusiness(unit);
    }
    @AfterEach void tearDown() throws Exception { if (database != null) { database.close(); } }

    @Test void reportsAreIdenticalAcrossConnectionsAndIncomeSurvivesClosing() {
        var before = reporting(unit).statements(NOVEMBER, VIEWER);
        assertEquals(before, reporting(database.transactions()).statements(NOVEMBER, VIEWER));
        closeNovember(unit);
        var after = reporting(database.transactions()).statements(NOVEMBER, VIEWER);
        assertEquals(before.incomeStatement().rows(), after.incomeStatement().rows());
        assertEquals(Money.of(7_000_000), after.incomeStatement().profit());
        assertEquals(Money.of(7_000_000), after.balanceSheet().equity());
        assertTrue(after.trialBalance().periodsClosed());
        assertEquals(6, reporting(database.transactions()).journal(NOVEMBER, VIEWER).entryCount());
        assertEquals(List.of("REPORT_CLOSE_REV", "REPORT_CLOSE_EXP", "REPORT_CLOSE_RES"), reporting(database.transactions()).journal(NOVEMBER, VIEWER).rows().stream()
                .filter(row -> row.closing()).map(row -> row.reference()).distinct().toList());
        var ledger = reporting(database.transactions()).ledger(new LedgerQuery(NOVEMBER, "131", "KH01", null), VIEWER);
        assertEquals(Money.of(6_000_000), ledger.closingBalance());
    }

    @Test void repeatedReportingDoesNotCreateOrUpdateAccountingFacts() {
        var before = facts();
        ReportingService service = reporting(database.transactions());
        for (int index = 0; index < 2; index++) {
            service.statements(NOVEMBER, VIEWER);
            service.journal(NOVEMBER, VIEWER);
            service.ledger(new LedgerQuery(NOVEMBER, "1111", null, "CASH01"), VIEWER);
        }
        assertEquals(before, facts());
        assertThrows(AccessDeniedException.class, () -> service.statements(NOVEMBER, 5));
        assertEquals(before, facts());
    }

    @Test void debtMatchingAndItsCancellationDoNotChangeFinancialReportAmounts() {
        var before = reporting(unit).statements(NOVEMBER, VIEWER);
        JournalEntry sale = unit.execute(session -> session.journal().findAll().stream().filter(entry -> entry.getContent().reference().equals("REPORT_SALE")).findFirst().orElseThrow());
        JournalEntry receipt = unit.execute(session -> session.journal().findAll().stream().filter(entry -> entry.getContent().reference().equals("REPORT_RECEIPT")).findFirst().orElseThrow());
        var allocation = allocations(unit).allocate(request(sale, receipt, 4_000_000), CHIEF);
        assertEquals(before, reporting(database.transactions()).statements(NOVEMBER, VIEWER));
        allocations(unit).cancel(allocation.id(), DATE.plusDays(1), "Unmatch debt", CHIEF);
        assertEquals(before, reporting(database.transactions()).statements(NOVEMBER, VIEWER));
    }

    @Test void grossPartnerBalancesAndAdvanceReclassificationsSurviveSqlRehydration() {
        vn.ketoan.ClosingTestData.general(unit, "ADVANCE", VoucherLine.debit("1111", Money.of(2_000_000), null, "CASH01"),
                VoucherLine.credit("131", Money.of(2_000_000), "KH02", null));
        var reports = reporting(database.transactions()).statements(NOVEMBER, VIEWER);
        var receivable = reports.trialBalance().rows().stream().filter(row -> row.accountCode().equals("131")).findFirst().orElseThrow();
        assertEquals(Money.of(6_000_000), receivable.closing().debit());
        assertEquals(Money.of(2_000_000), receivable.closing().credit());
        assertTrue(reports.balanceSheet().rows().stream().anyMatch(row -> "KH02".equals(row.partnerCode())
                && row.section() == BalanceSheetSection.LIABILITY && row.reclassified()));
        assertEquals(Money.of(12_000_000), reports.balanceSheet().assets());
        assertEquals(Money.of(5_000_000), reports.balanceSheet().liabilities());
    }

    @Test void revenueReversalsAndMultiMonthPrepaidExpensesAreReportedInTheirOwnPeriods() {
        try (PrintStream output = new PrintStream(new ByteArrayOutputStream())) {
            CorrectionDemo.run(output, database.transactions(), CLOCK);
            PrepaidDemo.run(output, database.transactions(), CLOCK);
        }
        var reporting = reporting(database.transactions());
        assertEquals(Money.of(5_000_000), reporting.incomeStatement(NOVEMBER, VIEWER).profit());
        var prepaid = reporting.balanceSheet(NOVEMBER.to(), VIEWER).rows().stream().filter(row -> "242".equals(row.accountCode())).findFirst().orElseThrow();
        assertEquals(Money.of(4_000_000), prepaid.amount());
        closeNovember(unit);
        assertEquals(Money.of(5_000_000), reporting(database.transactions()).incomeStatement(NOVEMBER, VIEWER).profit());
        var december = ReportPeriod.month(YearMonth.from(DATE).plusMonths(1));
        assertEquals(Money.of(2_000_000), reporting(database.transactions()).incomeStatement(december, VIEWER).expense());
        var january = ReportPeriod.month(YearMonth.from(DATE).plusMonths(2));
        assertEquals(Money.of(2_000_000), reporting(database.transactions()).incomeStatement(january, VIEWER).expense());
        assertTrue(reporting(database.transactions()).balanceSheet(january.to(), VIEWER).rows().stream().noneMatch(row -> "242".equals(row.accountCode())));
    }

    private List<String> facts() {
        return database.transactions().execute(session -> {
            var values = new java.util.ArrayList<String>();
            session.documents().findAll().forEach(document -> values.add(document.getId() + ":" + document.getVersion() + ":" + document.getStatus()));
            session.journal().findAll().forEach(entry -> values.add(entry.getId() + ":" + entry.getContent()));
            session.closings().findAll().forEach(batch -> values.add(batch.toString()));
            session.closings().findEvents().forEach(event -> values.add(event.toString()));
            values.sort(String::compareTo);
            return List.copyOf(values);
        });
    }
}
