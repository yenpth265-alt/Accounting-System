package vn.ketoan.application.service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.ReversalRequest;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.report.AccountLedgerGenerator;
import vn.ketoan.domain.report.BalanceSheetGenerator;
import vn.ketoan.domain.report.BalanceSheetSection;
import vn.ketoan.domain.report.GeneralJournalGenerator;
import vn.ketoan.domain.report.IncomeStatementGenerator;
import vn.ketoan.domain.report.LedgerQuery;
import vn.ketoan.domain.report.ReportContext;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.domain.report.TrialBalanceGenerator;
import vn.ketoan.domain.report.TrialBalanceRow;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;
import static org.junit.jupiter.api.Assertions.*;
import static vn.ketoan.PostingTestData.approved;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.ReportingTestData.*;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.VIEWER;

class ReportingServiceTest {
    private InMemoryUnitOfWork unit;
    private ReportingService reports;
    @BeforeEach void setUp() { unit = transactions(); seedBusiness(unit); reports = reporting(unit); }

    @Test void journalTrialBalanceIncomeAndFinancialPositionReconcileForTheSamePostedBusiness() {
        var statements = reports.statements(NOVEMBER, VIEWER);
        assertEquals(Money.of(10_000_000), statements.incomeStatement().revenue());
        assertEquals(Money.of(3_000_000), statements.incomeStatement().expense());
        assertEquals(Money.of(7_000_000), statements.incomeStatement().profit());
        assertEquals(Money.of(10_000_000), statements.balanceSheet().assets());
        assertEquals(Money.of(3_000_000), statements.balanceSheet().liabilities());
        assertEquals(Money.of(7_000_000), statements.balanceSheet().equity());
        var journal = reports.journal(NOVEMBER, VIEWER);
        assertEquals(3, journal.entryCount());
        assertEquals(6, journal.rows().size());
        assertEquals(Money.of(17_000_000), journal.totals().debit());
        assertTrue(statements.trialBalance().openingTotals().balanced());
        assertTrue(statements.trialBalance().movementTotals().balanced());
        assertTrue(statements.trialBalance().closingTotals().balanced());
        assertFalse(statements.incomeStatement().periodsClosed());
    }

    @Test void closingZeroesTemporaryLedgerBalancesWhileIncomeAndFinancialPositionRemainCorrect() {
        var before = reports.statements(NOVEMBER, VIEWER);
        closeNovember(unit);
        var after = reports.statements(NOVEMBER, VIEWER);
        assertEquals(before.incomeStatement().rows(), after.incomeStatement().rows());
        assertEquals(before.balanceSheet().assets(), after.balanceSheet().assets());
        assertEquals(before.balanceSheet().equity(), after.balanceSheet().equity());
        for (String code : List.of("5113", "6427", "911")) { assertEquals(Money.ZERO, row(code).closing().net()); }
        assertEquals(Money.of(-7_000_000), row("4212").closing().net());
        assertTrue(after.incomeStatement().periodsClosed());
        assertTrue(after.balanceSheet().rows().stream().noneMatch(line -> line.accountCode() == null));
        assertEquals(6, reports.journal(NOVEMBER, VIEWER).entryCount());
        assertTrue(reports.journal(NOVEMBER, VIEWER).rows().stream().anyMatch(line -> line.closing()));
        assertEquals(List.of("REPORT_CLOSE_REV", "REPORT_CLOSE_EXP", "REPORT_CLOSE_RES"), reports.journal(NOVEMBER, VIEWER).rows().stream()
                .filter(line -> line.closing()).map(line -> line.reference()).distinct().toList());
        var resultLedger = reports.ledger(LedgerQuery.account(NOVEMBER, "911"), VIEWER);
        assertEquals(Money.of(-10_000_000), resultLedger.rows().getFirst().runningBalance());
        assertEquals(Money.ZERO, resultLedger.closingBalance());
        var historical = reports.balanceSheet(DATE, VIEWER);
        assertTrue(historical.rows().stream().anyMatch(line -> line.accountCode() == null && line.amount().equals(Money.of(7_000_000))));
    }

    @Test void customerAndSupplierAdvancesArePresentedSeparatelyWithoutOffsettingOtherPartners() {
        vn.ketoan.ClosingTestData.general(unit, "ADVANCE_CUSTOMER", VoucherLine.debit("1111", Money.of(2_000_000), null, "CASH01"),
                VoucherLine.credit("131", Money.of(2_000_000), "KH02", null));
        vn.ketoan.ClosingTestData.general(unit, "ADVANCE_SUPPLIER", VoucherLine.debit("331", Money.of(2_000_000), "DT01", null),
                VoucherLine.credit("1111", Money.of(2_000_000), null, "CASH01"));
        var sheet = reports.balanceSheet(NOVEMBER.to(), VIEWER);
        assertTrue(sheet.rows().stream().anyMatch(line -> "KH01".equals(line.partnerCode()) && line.section() == BalanceSheetSection.ASSET && line.amount().equals(Money.of(6_000_000))));
        assertTrue(sheet.rows().stream().anyMatch(line -> "KH02".equals(line.partnerCode()) && line.section() == BalanceSheetSection.LIABILITY && line.reclassified() && line.amount().equals(Money.of(2_000_000))));
        assertTrue(sheet.rows().stream().anyMatch(line -> "DT01".equals(line.partnerCode()) && line.section() == BalanceSheetSection.ASSET && line.reclassified()));
        assertEquals(Money.of(12_000_000), sheet.assets());
        assertEquals(Money.of(5_000_000), sheet.liabilities());
        assertEquals(Money.of(6_000_000), row("131").closing().debit());
        assertEquals(Money.of(2_000_000), row("131").closing().credit());
        assertEquals(Money.of(2_000_000), row("331").closing().debit());
        assertEquals(Money.of(3_000_000), row("331").closing().credit());
    }

    @Test void ledgersCalculateOpeningBalancesRunningBalancesAndInclusiveDateCutoffs() {
        LocalDate next = DATE.plusDays(1);
        var document = approved(unit, new ReceiptCreator(), new Draft("NEXT_DAY_RECEIPT", next, next, "Next day collection", List.of(
                VoucherLine.debit("1111", Money.of(2_000_000), null, "CASH01"), VoucherLine.credit("131", Money.of(2_000_000), "KH01", null))));
        posting(unit).post(document.getId(), document.getVersion(), CHIEF);
        var range = new ReportPeriod(next, next);
        var ledger = reports.ledger(new LedgerQuery(range, "131", "kh01", null), VIEWER);
        assertEquals(Money.of(6_000_000), ledger.openingBalance());
        assertEquals(Money.of(4_000_000), ledger.closingBalance());
        assertEquals(Money.of(4_000_000), ledger.rows().getFirst().runningBalance());
        assertEquals(1, ledger.rows().size());
        assertEquals(1, reports.journal(range, VIEWER).entryCount());
        assertEquals(Money.ZERO, reports.incomeStatement(range, VIEWER).revenue());
        var cash = reports.ledger(new LedgerQuery(range, "1111", null, "CASH01"), VIEWER);
        assertEquals(Money.of(4_000_000), cash.openingBalance());
        assertEquals(Money.of(6_000_000), cash.closingBalance());
        assertEquals(3, reports.journal(new ReportPeriod(DATE, DATE), VIEWER).entryCount());
    }

    @Test void groupLedgersFollowParentLinksAndDoNotIncludeUnrelatedCodePrefixes() {
        unit.execute(session -> {
            session.accounts().insert(Account.group("GROUP", "Group", AccountType.ASSET, BalanceSide.DEBIT, null));
            session.accounts().insert(Account.posting("CHILD", "Child", AccountType.ASSET, BalanceSide.DEBIT, "GROUP", AccountTracking.NONE));
            session.accounts().insert(Account.posting("GROUP_OTHER", "Different root", AccountType.ASSET, BalanceSide.DEBIT, null, AccountTracking.NONE));
            return null;
        });
        vn.ketoan.ClosingTestData.general(unit, "CHILD_POST", VoucherLine.debit("CHILD", Money.of(5), null, null), VoucherLine.credit("4111", Money.of(5), null, null));
        vn.ketoan.ClosingTestData.general(unit, "OTHER_POST", VoucherLine.debit("GROUP_OTHER", Money.of(7), null, null), VoucherLine.credit("4111", Money.of(7), null, null));
        var ledger = reports.ledger(LedgerQuery.account(NOVEMBER, "GROUP"), VIEWER);
        assertEquals(Money.of(5), ledger.closingBalance());
        assertEquals(1, ledger.rows().size());
        assertTrue(reports.trialBalance(NOVEMBER, VIEWER).rows().stream().noneMatch(line -> line.accountCode().equals("GROUP")));
    }

    @Test void unpostedDraftsAndApprovedDocumentsDoNotEnterReports() {
        unit.execute(session -> workflow(session).create(new RevenueCreator(), vn.ketoan.PostingTestData.named("UNPOSTED_DRAFT", vn.ketoan.VoucherTestData.revenue()), OWNER));
        approved(unit, new RevenueCreator(), vn.ketoan.PostingTestData.named("UNPOSTED_APPROVED", vn.ketoan.VoucherTestData.revenue()));
        assertEquals(3, reports.journal(NOVEMBER, VIEWER).entryCount());
        assertEquals(Money.of(10_000_000), reports.incomeStatement(NOVEMBER, VIEWER).revenue());
    }

    @Test void aReversalInTheNextMonthChangesThatMonthsIncomeAndNotTheClosedMonthsIncome() {
        var source = unit.execute(session -> session.journal().findAll().stream().filter(entry -> entry.getContent().reference().equals("REPORT_SALE")).findFirst().orElseThrow());
        closeNovember(unit);
        YearMonth december = YearMonth.from(DATE).plusMonths(1);
        unit.execute(session -> { session.periods().insert(AccountingPeriod.open(december)); return null; });
        var reversal = vn.ketoan.CorrectionTestData.corrections(unit).createReversal(new ReversalRequest(UUID.randomUUID(), source.getSourceDocumentId(),
                "DEC_REVERSAL", december.atDay(1), "Cancel service in the open month"), OWNER);
        vn.ketoan.SettlementTestData.postDraft(unit, reversal);
        assertEquals(Money.of(7_000_000), reports.incomeStatement(NOVEMBER, VIEWER).profit());
        assertEquals(Money.of(-10_000_000), reports.incomeStatement(ReportPeriod.month(december), VIEWER).profit());
        assertEquals(Money.of(-3_000_000), reports.balanceSheet(december.atEndOfMonth(), VIEWER).equity());
        assertFalse(reports.statements(ReportPeriod.month(december), VIEWER).trialBalance().periodsClosed());
    }

    @Test void emptyLaterPeriodsCarryBalancesButDoNotRepeatThePreviousMonthsIncome() {
        closeNovember(unit);
        YearMonth december = YearMonth.from(DATE).plusMonths(1);
        unit.execute(session -> { session.periods().insert(AccountingPeriod.open(december)); return null; });
        var period = ReportPeriod.month(december);
        var statements = reports.statements(period, VIEWER);
        assertEquals(Money.ZERO, statements.incomeStatement().profit());
        assertTrue(reports.journal(period, VIEWER).rows().isEmpty());
        var account = statements.trialBalance().rows().stream().filter(row -> row.accountCode().equals("4212")).findFirst().orElseThrow();
        assertEquals(Money.of(7_000_000), account.opening().credit());
        assertEquals(account.opening(), account.closing());
        assertEquals(Money.of(7_000_000), statements.balanceSheet().equity());
    }

    @Test void inactiveCatalogsRemainAvailableForHistoricalReports() {
        unit.execute(session -> {
            session.accounts().update(session.accounts().findByCode("5113").orElseThrow().deactivate());
            session.accounts().update(session.accounts().findByCode("131").orElseThrow().deactivate());
            session.partners().update(session.partners().findByCode("KH01").orElseThrow().deactivate());
            session.moneyAccounts().update(session.moneyAccounts().findByCode("CASH01").orElseThrow().deactivate());
            return null;
        });
        assertEquals(Money.of(7_000_000), reports.incomeStatement(NOVEMBER, VIEWER).profit());
        assertEquals(Money.of(6_000_000), reports.ledger(new LedgerQuery(NOVEMBER, "131", "KH01", null), VIEWER).closingBalance());
        assertEquals(Money.of(4_000_000), reports.ledger(new LedgerQuery(NOVEMBER, "1111", null, "CASH01"), VIEWER).closingBalance());
    }

    @Test void reportQueriesRequirePermissionAndExistingFilterCatalogs() {
        assertThrows(AccessDeniedException.class, () -> reports.statements(NOVEMBER, 5));
        assertThrows(AccessDeniedException.class, () -> reports.journal(NOVEMBER, 999));
        assertThrows(IllegalArgumentException.class, () -> reports.ledger(LedgerQuery.account(NOVEMBER, "MISSING"), VIEWER));
        assertThrows(IllegalArgumentException.class, () -> reports.ledger(new LedgerQuery(NOVEMBER, "131", "MISSING", null), VIEWER));
        assertThrows(IllegalArgumentException.class, () -> reports.ledger(new LedgerQuery(NOVEMBER, "131", null, "CASH01"), VIEWER));
    }

    @Test void financialStatementsDelegateToInjectedGeneratorsWithinOneTransactionAndOneContext() {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<ReportContext> captured = new AtomicReference<>();
        UnitOfWork counted = new UnitOfWork() {
            @Override public <T> T execute(Function<AccountingSession, T> query) { reads.incrementAndGet(); return unit.execute(query); }
        };
        var service = new ReportingService(counted, new GeneralJournalGenerator(), new AccountLedgerGenerator(),
                (context, period) -> { captured.set(context); return new TrialBalanceGenerator().generate(context, period); },
                (context, period) -> { assertSame(captured.get(), context); return new IncomeStatementGenerator().generate(context, period); },
                (context, date) -> { assertSame(captured.get(), context); return new BalanceSheetGenerator().generate(context, date); });
        assertEquals(Money.of(7_000_000), service.statements(NOVEMBER, VIEWER).incomeStatement().profit());
        assertEquals(1, reads.get());
    }

    @Test void returnedReportsAreImmutableAndDoNotChangeWhenLaterPostingsArrive() {
        var before = reports.statements(NOVEMBER, VIEWER);
        assertThrows(UnsupportedOperationException.class, () -> before.incomeStatement().rows().clear());
        vn.ketoan.SettlementTestData.sale(unit, "ANOTHER_SALE", 2_000_000);
        assertEquals(Money.of(7_000_000), before.incomeStatement().profit());
        assertEquals(Money.of(9_000_000), reports.incomeStatement(NOVEMBER, VIEWER).profit());
    }

    @Test void accumulatedReportsPreserveWholeDongPrecisionBeyondOneSqlRowsAmountLimit() {
        Money amount = Money.of("9000000000000000000");
        for (String reference : List.of("LARGE1", "LARGE2")) {
            var draft = new Draft(reference, DATE, DATE, "Large amount", List.of(VoucherLine.debit("131", amount, "KH01", null), VoucherLine.credit("5113", amount, null, null)));
            var document = approved(unit, new RevenueCreator(), draft);
            posting(unit).post(document.getId(), document.getVersion(), CHIEF);
        }
        assertEquals(amount.add(amount).add(Money.of(10_000_000)), reports.incomeStatement(NOVEMBER, VIEWER).revenue());
        assertTrue(reports.trialBalance(NOVEMBER, VIEWER).movementTotals().balanced());
        assertEquals(reports.balanceSheet(NOVEMBER.to(), VIEWER).assets(), reports.balanceSheet(NOVEMBER.to(), VIEWER).liabilities().add(reports.balanceSheet(NOVEMBER.to(), VIEWER).equity()));
    }

    @Test void missingPeriodsAndInvalidDateRangesCannotBePresentedAsClosedReports() {
        closeNovember(unit);
        assertFalse(reports.trialBalance(new ReportPeriod(NOVEMBER.from().minusMonths(1), NOVEMBER.to()), VIEWER).periodsClosed());
        assertFalse(reports.balanceSheet(NOVEMBER.to().plusMonths(1), VIEWER).periodsClosed());
        assertThrows(IllegalArgumentException.class, () -> new ReportPeriod(DATE.plusDays(1), DATE));
        assertThrows(IllegalArgumentException.class, () -> new ReportPeriod(LocalDate.of(999, 1, 1), DATE));
    }

    @Test void ordinaryGeneralAdjustmentsAreIncludedEvenIfTheirReferenceLooksLikeAClosing() {
        vn.ketoan.ClosingTestData.general(unit, "KC_NOT_GENERATED", VoucherLine.debit("131", Money.of(2_000_000), "KH01", null),
                VoucherLine.credit("5113", Money.of(2_000_000), null, null));
        assertEquals(Money.of(12_000_000), reports.incomeStatement(NOVEMBER, VIEWER).revenue());
    }

    @Test void contraAssetsRetainTheirNegativeAssetPresentationInsteadOfBecomingLiabilities() {
        unit.execute(session -> {
            session.accounts().insert(Account.posting("ASSET_CONTRA", "Contra asset", AccountType.ASSET, BalanceSide.CREDIT, null, AccountTracking.NONE));
            return null;
        });
        vn.ketoan.ClosingTestData.general(unit, "CONTRA_ASSET", VoucherLine.debit("1111", Money.of(5), null, "CASH01"),
                VoucherLine.credit("ASSET_CONTRA", Money.of(5), null, null));
        var sheet = reports.balanceSheet(NOVEMBER.to(), VIEWER);
        assertTrue(sheet.rows().stream().anyMatch(row -> "ASSET_CONTRA".equals(row.accountCode()) && row.section() == BalanceSheetSection.ASSET
                && row.amount().equals(Money.of(-5)) && !row.reclassified()));
        assertEquals(Money.of(10_000_000), sheet.assets());
        assertEquals(Money.of(3_000_000), sheet.liabilities());
    }

    @Test void aMultiMonthIncomeStatementExcludesEveryMonthsGeneratedClosingTransfers() {
        closeNovember(unit);
        YearMonth december = YearMonth.from(DATE).plusMonths(1);
        unit.execute(session -> { session.periods().insert(AccountingPeriod.open(december)); return null; });
        var date = december.atDay(2);
        var document = approved(unit, new GeneralCreator(), new Draft("DEC_COST", date, date, "December expense", List.of(
                VoucherLine.debit("6427", Money.of(2_000_000), null, null), VoucherLine.credit("331", Money.of(2_000_000), "NCC01", null))));
        posting(unit).post(document.getId(), document.getVersion(), CHIEF);
        var closing = vn.ketoan.ClosingTestData.closing(unit);
        var batch = closing.prepare(new vn.ketoan.domain.ClosingRequest(UUID.randomUUID(), "DEC_CLOSE", december, "911", "4212"), OWNER);
        closing.approve(batch.id(), CHIEF);
        closing.close(batch.id(), CHIEF);
        var combined = reports.statements(new ReportPeriod(NOVEMBER.from(), december.atEndOfMonth()), VIEWER);
        assertEquals(Money.of(10_000_000), combined.incomeStatement().revenue());
        assertEquals(Money.of(5_000_000), combined.incomeStatement().expense());
        assertEquals(Money.of(5_000_000), combined.incomeStatement().profit());
        assertEquals(Money.of(5_000_000), combined.balanceSheet().equity());
        assertTrue(combined.incomeStatement().periodsClosed());
    }
    private TrialBalanceRow row(String code) { return reports.trialBalance(NOVEMBER, VIEWER).rows().stream().filter(row -> row.accountCode().equals(code)).findFirst().orElseThrow(); }
}
