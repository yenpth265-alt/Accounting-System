package vn.ketoan.application.service;

import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.report.CashFlowActivity;
import vn.ketoan.domain.report.CashFlowDirection;
import vn.ketoan.domain.report.ReportVersion;
import vn.ketoan.domain.report.SaveReportVersionRequest;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;
import static org.junit.jupiter.api.Assertions.*;
import static vn.ketoan.PostingTestData.approved;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.transactions;
import static vn.ketoan.ReportVersionTestData.*;
import static vn.ketoan.ReportingTestData.*;
import static vn.ketoan.WorkflowTestData.*;

class ReportVersionServiceTest {
    private InMemoryUnitOfWork unit;
    @BeforeEach void setUp() { unit = transactions(); seedBusiness(unit); }

    @Test void draftSnapshotsRemainDeeplyImmutableAfterClassificationAndClosing() {
        var request = snapshot("Draft before closing");
        var first = versions(unit).save(request, OWNER);
        String hash = first.documentChecksum();
        assertFalse(first.periodsClosed()); assertFalse(first.cashFlowComplete());
        assertThrows(UnsupportedOperationException.class, () -> first.document().tables().getFirst().rows().getFirst().set(0, "changed"));
        classifyReceipt(); closeNovember(unit);
        var second = versions(unit).save(snapshot("Closed and classified"), OWNER);
        assertEquals(2, second.version());
        assertTrue(second.periodsClosed()); assertTrue(second.cashFlowComplete());
        assertEquals(first, versions(unit).save(request, OWNER));
        assertEquals(hash, versions(unit).get(first.id(), VIEWER).documentChecksum());
        assertNotEquals(first.documentChecksum(), second.documentChecksum());
    }

    @Test void independentChiefCanPublishACompleteClosedSnapshotAndRetryWithoutDuplicateEvents() {
        var version = ready();
        var first = versions(unit).publish(version.id(), "Reviewed totals and cash flows", CHIEF);
        assertEquals(first, versions(unit).publish(version.id(), "Reviewed totals and cash flows", CHIEF));
        assertEquals(1, versions(unit).publications(VIEWER).size());
        assertThrows(IllegalArgumentException.class, () -> versions(unit).publish(version.id(), "Changed review", CHIEF));
    }

    @Test void openPeriodsAndUnclassifiedCashEachBlockPublication() {
        var open = versions(unit).save(snapshot("Open"), OWNER);
        assertThrows(IllegalStateException.class, () -> versions(unit).publish(open.id(), "Attempt", CHIEF));
        closeNovember(unit);
        var unclassified = versions(unit).save(snapshot("Closed but not classified"), OWNER);
        assertThrows(IllegalStateException.class, () -> versions(unit).publish(unclassified.id(), "Attempt", CHIEF));
        assertTrue(versions(unit).publications(VIEWER).isEmpty());
    }

    @Test void reclassificationMakesAnUnpublishedSnapshotStaleAndPreservesAlreadyPublishedHistory() {
        var first = ready();
        versions(unit).publish(first.id(), "First approval", CHIEF);
        var draft = versions(unit).save(snapshot("Awaiting review"), OWNER);
        classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 1, CashFlowActivity.FINANCING, CashFlowDirection.INFLOW, 4_000_000), OWNER);
        assertThrows(IllegalStateException.class, () -> versions(unit).publish(draft.id(), "Stale approval", CHIEF));
        assertEquals(first, versions(unit).get(first.id(), VIEWER));
        var latest = versions(unit).save(snapshot("Reclassified version"), OWNER);
        versions(unit).publish(latest.id(), "Revised approval", CHIEF);
        assertEquals(3, latest.version());
        assertEquals(2, versions(unit).publications(VIEWER).size());
    }

    @Test void anOlderIdenticalDraftCannotBePublishedAfterANewerVersion() {
        var first = ready();
        var second = versions(unit).save(snapshot("Version two"), OWNER);
        assertEquals(first.sourceChecksum(), second.sourceChecksum());
        versions(unit).publish(second.id(), "Latest approved", CHIEF);
        assertThrows(IllegalStateException.class, () -> versions(unit).publish(first.id(), "Older draft", CHIEF));
    }

    @Test void futurePostingsDoNotInvalidateAClosedHistoricalSnapshot() {
        var version = ready();
        YearMonth month = YearMonth.from(NOVEMBER.to()).plusMonths(1);
        unit.execute(session -> { session.periods().insert(AccountingPeriod.open(month)); return null; });
        var date = month.atDay(2);
        var document = approved(unit, new ReceiptCreator(), new Draft("FUTURE_RECEIPT", date, date, "Collection next month", List.of(
                VoucherLine.debit("1111", Money.of(2_000_000), null, "CASH01"), VoucherLine.credit("131", Money.of(2_000_000), "KH01", null))));
        posting(unit).post(document.getId(), document.getVersion(), CHIEF);
        versions(unit).publish(version.id(), "Historical report remains unchanged", CHIEF);
        assertEquals(1, versions(unit).publications(VIEWER).size());
    }

    @Test void changingAReportedAccountNameRequiresANewSnapshotWhileTheOldNameRemainsStored() {
        var version = ready();
        unit.execute(session -> { session.accounts().update(session.accounts().findByCode("5113").orElseThrow().rename("Renamed revenue")); return null; });
        assertThrows(IllegalStateException.class, () -> versions(unit).publish(version.id(), "Old document", CHIEF));
        assertEquals(version.document(), versions(unit).get(version.id(), VIEWER).document());
        assertFalse(version.document().tables().stream().flatMap(table -> table.rows().stream()).flatMap(List::stream).anyMatch(value -> value.equals("Renamed revenue")));
    }

    @Test void requestRetriesValidateInputAndReadersCannotSaveOrPublish() {
        var request = snapshot("Title");
        versions(unit).save(request, OWNER);
        assertThrows(IllegalArgumentException.class, () -> versions(unit).save(new SaveReportVersionRequest(request.id(), NOVEMBER, "Changed title"), OWNER));
        assertThrows(AccessDeniedException.class, () -> versions(unit).save(snapshot("Denied"), VIEWER));
        assertThrows(AccessDeniedException.class, () -> versions(unit).publish(request.id(), "Denied", VIEWER));
        assertThrows(AccessDeniedException.class, () -> versions(unit).get(request.id(), ADMIN));
    }

    @Test void preparerCannotPublishEvenWithBothAccountingAndChiefRoles() {
        unit.execute(session -> { session.users().update(new User(OWNER, "user1", "Combined", Set.of(UserRole.ACCOUNTANT, UserRole.CHIEF))); return null; });
        var version = ready();
        assertThrows(AccessDeniedException.class, () -> versions(unit).publish(version.id(), "Self approval", OWNER));
    }

    private void classifyReceipt() { classifications(unit).classify(classification(entry(unit, "REPORT_RECEIPT"), 0, CashFlowActivity.OPERATING, CashFlowDirection.INFLOW, 4_000_000), OWNER); }
    private ReportVersion ready() { classifyReceipt(); closeNovember(unit); return versions(unit).save(snapshot("Ready for review"), OWNER); }
}
