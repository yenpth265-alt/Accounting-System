package vn.ketoan.application.service;

import java.time.Clock;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.WorkflowTestData.Environment;
import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.Voucher;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.WorkflowTestData.ADMIN;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.COMBINED;
import static vn.ketoan.WorkflowTestData.OTHER;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.VIEWER;
import static vn.ketoan.WorkflowTestData.environment;
import static vn.ketoan.WorkflowTestData.receiptDraft;

class VoucherWorkflowServiceTest {

    private Environment environment;
    private VoucherWorkflowService service;

    @BeforeEach
    void setUp() {
        environment = environment();
        service = environment.service();
    }

    @Test
    void incompleteDraftCanBeSavedButCannotBeSubmittedUntilFixed() {
        Draft incomplete = receiptDraft("WF001", 4_000_000).withLines(List.of());
        VoucherDocument draft = service.create(new ReceiptCreator(), incomplete, OWNER);
        assertThrows(IllegalArgumentException.class, () -> service.submit(draft.getId(), 0, OWNER));
        assertUnchanged(draft);

        VoucherDocument edited = service.edit(draft.getId(), 0, new ReceiptCreator(),
                receiptDraft("WF001", 4_000_000), OWNER);
        VoucherDocument pending = service.submit(edited.getId(), edited.getVersion(), OWNER);
        VoucherDocument approved = service.approve(pending.getId(), pending.getVersion(), CHIEF);

        assertEquals(VoucherStatus.APPROVED, approved.getStatus());
        assertEquals(3, approved.getVersion());
        assertEquals(4, service.history(approved.getId(), VIEWER).size());
    }

    @Test
    void approvalRevalidatesCurrentMoneyAccountAccountAndPeriod() {
        VoucherDocument created = create();
        VoucherDocument pending = service.submit(created.getId(), 0, OWNER);
        MoneyAccount fund = environment.catalogs().moneyAccounts().findByCode("CASH01").orElseThrow();
        environment.catalogs().moneyAccounts().update(fund.deactivate());
        assertThrows(IllegalStateException.class, () -> service.approve(pending.getId(), 1, CHIEF));
        assertUnchanged(pending);
        environment.catalogs().moneyAccounts().update(fund);

        Account account = environment.catalogs().accounts().findByCode("1111").orElseThrow();
        environment.catalogs().accounts().update(account.deactivate());
        assertThrows(IllegalStateException.class, () -> service.approve(pending.getId(), 1, CHIEF));
        assertUnchanged(pending);
        environment.catalogs().accounts().update(account);

        AccountingPeriod period = environment.catalogs().periods().findByMonth(YearMonth.of(2026, 11)).orElseThrow();
        environment.catalogs().periods().update(period.beginClosing());
        assertThrows(IllegalStateException.class, () -> service.approve(pending.getId(), 1, CHIEF));
        assertUnchanged(pending);
        environment.catalogs().periods().update(period);
        assertEquals(VoucherStatus.APPROVED, service.approve(pending.getId(), 1, CHIEF).getStatus());
    }

    @ParameterizedTest
    @ValueSource(longs = {VIEWER, ADMIN, CHIEF})
    void readOnlyOrUnassignedRolesCannotCreateVouchers(long actorId) {
        assertThrows(AccessDeniedException.class,
                () -> service.create(new ReceiptCreator(), receiptDraft("WF001", 4_000_000), actorId));
        assertTrue(environment.documents().findAll().isEmpty());
    }

    @Test
    void nonOwnerCannotEditOrSubmitAndCannotBypassChecksThroughCreatorChoice() {
        VoucherDocument draft = create();
        assertAll(
                () -> assertThrows(AccessDeniedException.class,
                        () -> service.edit(draft.getId(), 0, new ReceiptCreator(),
                                receiptDraft("WF001", 5_000_000), OTHER)),
                () -> assertThrows(AccessDeniedException.class, () -> service.submit(draft.getId(), 0, OTHER)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.edit(draft.getId(), 0, new GeneralCreator(),
                                receiptDraft("WF001", 4_000_000), OWNER)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.edit(draft.getId(), 0, new ReceiptCreator(),
                                receiptDraft("OTHER", 4_000_000), OWNER)));
        assertUnchanged(draft);
    }

    @Test
    void combinedRoleCreatorStillNeedsAnotherReviewer() {
        VoucherDocument draft = service.create(new ReceiptCreator(), receiptDraft("WF001", 4_000_000), COMBINED);
        VoucherDocument pending = service.submit(draft.getId(), 0, COMBINED);

        assertThrows(AccessDeniedException.class, () -> service.approve(pending.getId(), 1, COMBINED));
        assertUnchanged(pending);
        assertEquals(VoucherStatus.APPROVED, service.approve(pending.getId(), 1, CHIEF).getStatus());
    }

    @Test
    void approvedContentRequiresWithdrawalBeforeCreatorCanEditIt() {
        VoucherDocument draft = create();
        VoucherDocument pending = service.submit(draft.getId(), 0, OWNER);
        VoucherDocument approved = service.approve(pending.getId(), 1, CHIEF);
        assertThrows(IllegalStateException.class,
                () -> service.edit(approved.getId(), 2, new ReceiptCreator(),
                        receiptDraft("WF001", 5_000_000), OWNER));
        assertUnchanged(approved);

        VoucherDocument returned = service.withdrawApproval(approved.getId(), 2, CHIEF, "Correct amount");
        VoucherDocument edited = service.edit(returned.getId(), 3, new ReceiptCreator(),
                receiptDraft("WF001", 5_000_000), OWNER);
        assertEquals(VoucherStatus.RETURNED, edited.getStatus());
        assertEquals(5, edited.getHistory().size());
    }

    @Test
    void rejectedReasonsOrStatesNeverAppendPartialHistory() {
        VoucherDocument draft = create();
        assertThrows(IllegalArgumentException.class, () -> service.cancel(draft.getId(), 0, OWNER, " "));
        assertUnchanged(draft);
        VoucherDocument pending = service.submit(draft.getId(), 0, OWNER);
        assertThrows(IllegalArgumentException.class,
                () -> service.returnForRevision(pending.getId(), 1, CHIEF, null));
        assertUnchanged(pending);
        VoucherDocument returned = service.returnForRevision(pending.getId(), 1, CHIEF, "Revise details");
        VoucherDocument cancelled = service.cancel(returned.getId(), 2, OWNER, "No longer needed");
        assertThrows(IllegalStateException.class, () -> service.submit(cancelled.getId(), 3, OWNER));
        assertUnchanged(cancelled);
    }

    @Test
    void userPermissionsAreReloadedRatherThanTrustedFromAnOldView() {
        VoucherDocument draft = create();
        User owner = environment.users().findById(OWNER).orElseThrow();
        environment.users().update(owner.deactivate());
        assertThrows(AccessDeniedException.class, () -> service.submit(draft.getId(), 0, OWNER));
        assertUnchanged(draft);
        environment.users().update(new User(OWNER, owner.getUsername(), owner.getDisplayName(), Set.of(UserRole.VIEWER)));
        assertThrows(AccessDeniedException.class, () -> service.submit(draft.getId(), 0, OWNER));
        assertUnchanged(draft);
    }

    @Test
    void viewerCanReadSnapshotsAndHistoryButAdministratorDoesNotGainAccountingAccess() {
        VoucherDocument draft = create();
        assertEquals(draft, service.get(draft.getId(), VIEWER));
        assertEquals(1, service.list(VIEWER).size());
        assertEquals(1, service.history(draft.getId(), VIEWER).size());
        assertAll(
                () -> assertThrows(AccessDeniedException.class, () -> service.get(draft.getId(), ADMIN)),
                () -> assertThrows(AccessDeniedException.class, () -> service.get(draft.getId(), 999)),
                () -> assertThrows(AccessDeniedException.class,
                        () -> service.cancel(draft.getId(), 0, VIEWER, "Reason")),
                () -> assertThrows(UnsupportedOperationException.class, service.list(VIEWER)::clear));
    }

    @Test
    void staleVersionIsRejectedBeforeContentValidation() {
        VoucherDocument draft = create();
        VoucherDocument edited = service.edit(draft.getId(), 0, new ReceiptCreator(),
                receiptDraft("WF001", 5_000_000), OWNER);
        RecordingValidator validator = new RecordingValidator();
        VoucherWorkflowService guarded = new VoucherWorkflowService(environment.users(), environment.documents(),
                validator, Clock.fixed(TIME, ZoneOffset.UTC));

        assertThrows(StaleVersionException.class, () -> guarded.submit(edited.getId(), 0, OWNER));
        assertEquals(0, validator.calls);
        assertUnchanged(edited);
    }

    @Test
    void referenceCannotBeReusedEvenAfterCancellation() {
        VoucherDocument draft = create();
        service.cancel(draft.getId(), 0, OWNER, "Duplicate business event");

        assertThrows(IllegalStateException.class,
                () -> service.create(new ReceiptCreator(), receiptDraft("WF001", 4_000_000), OWNER));
        assertEquals(1, service.list(VIEWER).size());
    }

    private VoucherDocument create() {
        return service.create(new ReceiptCreator(), receiptDraft("WF001", 4_000_000), OWNER);
    }

    private void assertUnchanged(VoucherDocument expected) {
        assertEquals(expected, environment.documents().findById(expected.getId()).orElseThrow());
    }

    private static final class RecordingValidator implements VoucherValidator {
        private int calls;

        @Override
        public void validate(Voucher voucher) {
            calls++;
        }
    }
}
