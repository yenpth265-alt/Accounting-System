package vn.ketoan.infrastructure.jdbc;

import java.time.YearMonth;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.report.CashFlowActivity;
import vn.ketoan.domain.report.CashFlowDirection;
import vn.ketoan.domain.report.ReportPublication;
import vn.ketoan.domain.report.ReportVersion;
import vn.ketoan.domain.value.Money;
import static org.junit.jupiter.api.Assertions.*;
import static vn.ketoan.ReportVersionTestData.*;
import static vn.ketoan.ReportingTestData.*;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.VIEWER;

class JdbcReportVersionTest {
    private JdbcTestDatabase database;
    private UnitOfWork unit;
    @BeforeEach void setUp() throws Exception {
        database = new JdbcTestDatabase(); database.migrateAndSeed(); unit = database.transactions(); seedBusiness(unit);
    }
    @AfterEach void tearDown() throws Exception { if (database != null) { database.close(); } }

    @Test void classificationsSnapshotsAndPublicationSurviveNewConnectionsWithoutRecalculatingOldVersions() {
        var first = versions(unit).save(snapshot("Bản nháp & <đối chiếu>"), OWNER);
        classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        closeNovember(unit);
        var second = versions(database.transactions()).save(snapshot("Bản đã phân loại"), OWNER);
        var publication = versions(database.transactions()).publish(second.id(), "Đã kiểm tra & đối chiếu", CHIEF);
        assertEquals(first, versions(database.transactions()).get(first.id(), VIEWER));
        assertEquals(second, versions(database.transactions()).get(second.id(), VIEWER));
        assertEquals(List.of(publication), versions(database.transactions()).publications(VIEWER));
        classifications(database.transactions()).classify(classification(entry(unit, "REPORT_RECEIPT"), 1, CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        assertEquals(second.document(), versions(database.transactions()).get(second.id(), VIEWER).document());
        assertEquals(2, classifications(database.transactions()).history(VIEWER).size());
        assertEquals(Money.of(4_000_000), reporting(database.transactions()).cashFlow(NOVEMBER, VIEWER).net(CashFlowActivity.FINANCING));
    }

    @Test void invalidClassificationRollsBackHeaderAndSplitRowsTogether() {
        assertThrows(IllegalArgumentException.class, () -> classifications(unit).classify(new vn.ketoan.domain.report.CashFlowClassificationRequest(UUID.randomUUID(), UUID.randomUUID(), 0,
                "Missing source", Money.ZERO, List.of(new vn.ketoan.domain.report.CashFlowSplit(CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, Money.of(1)))), OWNER));
        assertThrows(IllegalArgumentException.class, () -> classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 3_000_000), OWNER));
        assertTrue(classifications(database.transactions()).history(VIEWER).isEmpty());
        classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        assertEquals(1, classifications(database.transactions()).history(VIEWER).size());
    }

    @Test void aFailedSnapshotTransactionDoesNotConsumeAVersionNumberOrLeaveContentBehind() {
        var first = ready();
        assertThrows(IllegalStateException.class, () -> unit.execute(session -> {
            session.reportVersions().append(new ReportVersion(UUID.randomUUID(), first.period(), 2, "Interrupted", first.sourceChecksum(), first.documentChecksum(),
                    first.document(), first.periodsClosed(), first.cashFlowComplete(), OWNER, TIME));
            throw new IllegalStateException("Interrupted after SQL insert");
        }));
        assertEquals(1, versions(database.transactions()).versions(NOVEMBER, VIEWER).size());
        assertEquals(2, versions(database.transactions()).save(snapshot("Retry"), OWNER).version());
    }

    @Test void aFailedPublicationTransactionCanBeRetriedWithoutPartialHistory() {
        var first = ready();
        assertThrows(IllegalStateException.class, () -> unit.execute(session -> {
            session.reportVersions().appendPublication(new ReportPublication(first.id(), "Interrupted", CHIEF, TIME));
            throw new IllegalStateException("Interrupted after publication insert");
        }));
        assertTrue(versions(database.transactions()).publications(VIEWER).isEmpty());
        versions(database.transactions()).publish(first.id(), "Successful review", CHIEF);
        assertEquals(1, versions(database.transactions()).publications(VIEWER).size());
    }

    @Test void concurrentConnectionsAllocateDistinctConsecutiveVersionNumbers() throws Exception {
        ready();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> versions(database.transactions()).save(snapshot("Concurrent A"), OWNER));
            var second = pool.submit(() -> versions(database.transactions()).save(snapshot("Concurrent B"), OWNER));
            var numbers = List.of(first.get(30, TimeUnit.SECONDS).version(), second.get(30, TimeUnit.SECONDS).version()).stream().sorted().toList();
            assertEquals(List.of(2, 3), numbers);
        }
        assertEquals(3, versions(database.transactions()).versions(NOVEMBER, VIEWER).size());
    }

    @Test void alteredStoredAmountsAreDetectedByTheDocumentChecksum() throws Exception {
        var first = ready();
        database.execute("UPDATE acc_report_versions SET document_xml=REPLACE(document_xml,'7000000','7000001')");
        assertThrows(IllegalArgumentException.class, () -> versions(database.transactions()).get(first.id(), VIEWER));
    }

    @Test void bothNewRepositoryViewsAreScopedAndCaughtFailuresForceRollback() {
        var escapedCash = unit.execute(AccountingSession::cashFlowClassifications);
        var escapedReports = unit.execute(AccountingSession::reportVersions);
        assertThrows(IllegalStateException.class, escapedCash::findAll);
        assertThrows(IllegalStateException.class, escapedReports::findAll);
        assertThrows(IllegalStateException.class, () -> unit.execute(session -> {
            session.periods().insert(AccountingPeriod.open(YearMonth.from(NOVEMBER.to()).plusMonths(1)));
            try { session.reportVersions().findById(null); } catch (NullPointerException expected) { }
            return null;
        }));
        assertTrue(database.transactions().<Boolean>execute(session -> session.periods().findByMonth(YearMonth.from(NOVEMBER.to()).plusMonths(1)).isEmpty()));
    }

    private ReportVersion ready() {
        classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        closeNovember(unit);
        return versions(unit).save(snapshot("Ready"), OWNER);
    }
}
