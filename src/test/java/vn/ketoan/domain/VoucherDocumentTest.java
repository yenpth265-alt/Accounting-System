package vn.ketoan.domain;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.COMBINED;
import static vn.ketoan.WorkflowTestData.OTHER;
import static vn.ketoan.WorkflowTestData.OWNER;
import static vn.ketoan.WorkflowTestData.TIME;
import static vn.ketoan.WorkflowTestData.content;
import static vn.ketoan.WorkflowTestData.receiptDraft;
import static vn.ketoan.WorkflowTestData.user;

class VoucherDocumentTest {

    private final User owner = user(OWNER, UserRole.ACCOUNTANT);
    private final User chief = user(CHIEF, UserRole.CHIEF);

    @Test
    void creationRecordsOwnerVersionAndImmutableContentSnapshot() {
        VoucherDocument document = create();

        assertAll(
                () -> assertEquals(VoucherStatus.DRAFT, document.getStatus()),
                () -> assertEquals(0, document.getVersion()),
                () -> assertEquals(OWNER, document.getCreatedBy()),
                () -> assertEquals(TIME, document.getCreatedAt()),
                () -> assertEquals(VoucherAction.CREATED, document.getHistory().getFirst().action()),
                () -> assertEquals(Money.of(4_000_000),
                        document.getHistory().getFirst().content().lines().getFirst().amount()),
                () -> assertThrows(UnsupportedOperationException.class, document.getHistory()::clear),
                () -> assertThrows(UnsupportedOperationException.class,
                        document.getHistory().getFirst().content().lines()::clear));
    }

    @Test
    void returnEditResubmitApprovePreservesEarlierContentAndHistory() {
        VoucherDocument original = create();
        VoucherDocument pending = original.submit(owner, TIME);
        VoucherDocument returned = pending.returnForRevision(chief, "Correct the amount", TIME);
        Voucher replacement = new ReceiptCreator().create(receiptDraft("WF001", 5_000_000));
        VoucherDocument edited = returned.edit(owner, replacement, TIME);
        VoucherDocument approved = edited.submit(owner, TIME).approve(chief, TIME);

        assertAll(
                () -> assertEquals(VoucherStatus.DRAFT, original.getStatus()),
                () -> assertEquals(VoucherStatus.RETURNED, edited.getStatus()),
                () -> assertEquals(VoucherStatus.APPROVED, approved.getStatus()),
                () -> assertEquals(5, approved.getVersion()),
                () -> assertEquals(6, approved.getHistory().size()),
                () -> assertEquals(CHIEF, approved.getApprovedBy().orElseThrow()),
                () -> assertEquals(TIME, approved.getApprovedAt().orElseThrow()),
                () -> assertEquals(Money.of(4_000_000), approved.getHistory().getFirst()
                        .content().lines().getFirst().amount()),
                () -> assertEquals(Money.of(5_000_000), approved.getHistory().get(3)
                        .content().lines().getFirst().amount()));
        for (int i = 0; i < approved.getHistory().size(); i++) {
            assertEquals(i, approved.getHistory().get(i).version());
        }
    }

    @Test
    void onlyCreatorCanEditOrSubmitTheirDraft() {
        VoucherDocument document = create();
        User other = user(OTHER, UserRole.ACCOUNTANT);

        assertAll(
                () -> assertThrows(AccessDeniedException.class, () -> document.edit(other, content(), TIME)),
                () -> assertThrows(AccessDeniedException.class, () -> document.submit(other, TIME)));
        assertEquals(1, document.getHistory().size());
    }

    @Test
    void evenCombinedRolesCannotReviewTheirOwnVoucher() {
        User combined = user(COMBINED, UserRole.ACCOUNTANT, UserRole.CHIEF);
        VoucherDocument pending = VoucherDocument.create(UUID.randomUUID(), content(), combined, TIME)
                .submit(combined, TIME);

        assertAll(
                () -> assertThrows(AccessDeniedException.class, () -> pending.approve(combined, TIME)),
                () -> assertThrows(AccessDeniedException.class,
                        () -> pending.returnForRevision(combined, "Reason", TIME)));
        VoucherDocument approved = pending.approve(chief, TIME);
        assertThrows(AccessDeniedException.class,
                () -> approved.withdrawApproval(combined, "Reason", TIME));
    }

    @Test
    void pendingAndApprovedContentCannotBeEditedDirectly() {
        VoucherDocument pending = create().submit(owner, TIME);
        VoucherDocument approved = pending.approve(chief, TIME);

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> pending.edit(owner, content(), TIME)),
                () -> assertThrows(IllegalStateException.class, () -> approved.edit(owner, content(), TIME)),
                () -> assertThrows(IllegalStateException.class, () -> approved.submit(owner, TIME)),
                () -> assertThrows(IllegalStateException.class, () -> approved.cancel(owner, "Reason", TIME)));
    }

    @Test
    void withdrawalReturnsToRevisionAndKeepsPriorApprovalEvidence() {
        VoucherDocument approved = create().submit(owner, TIME).approve(chief, TIME);
        VoucherDocument returned = approved.withdrawApproval(chief, "Need a correction", TIME);

        assertEquals(VoucherStatus.RETURNED, returned.getStatus());
        assertTrue(returned.getApprovedBy().isEmpty());
        assertTrue(returned.getApprovedAt().isEmpty());
        assertEquals(VoucherAction.APPROVED, returned.getHistory().get(2).action());
        assertEquals("Need a correction", returned.getHistory().getLast().note());
        returned.edit(owner, content(), TIME).submit(owner, TIME).approve(chief, TIME);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void returnCancellationAndWithdrawalRequireReasons(String reason) {
        VoucherDocument draft = create();
        VoucherDocument pending = draft.submit(owner, TIME);
        VoucherDocument approved = pending.approve(chief, TIME);

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> draft.cancel(owner, reason, TIME)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> pending.returnForRevision(chief, reason, TIME)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> approved.withdrawApproval(chief, reason, TIME)));
    }

    @Test
    void cancelledDocumentCannotBeEditedSubmittedOrReviewed() {
        VoucherDocument cancelled = create().cancel(owner, "Duplicate draft", TIME);

        assertAll(
                () -> assertThrows(IllegalStateException.class, () -> cancelled.edit(owner, content(), TIME)),
                () -> assertThrows(IllegalStateException.class, () -> cancelled.submit(owner, TIME)),
                () -> assertThrows(IllegalStateException.class, () -> cancelled.approve(chief, TIME)),
                () -> assertThrows(IllegalStateException.class, () -> cancelled.cancel(owner, "Again", TIME)));
    }

    @Test
    void cancellationIsLimitedToOwnerOrChiefAndEditableStates() {
        VoucherDocument draft = create();
        assertThrows(AccessDeniedException.class,
                () -> draft.cancel(user(OTHER, UserRole.ACCOUNTANT), "Reason", TIME));
        assertEquals(VoucherStatus.CANCELLED, draft.cancel(chief, "Chief cancellation", TIME).getStatus());
        assertThrows(IllegalStateException.class,
                () -> draft.submit(owner, TIME).cancel(chief, "Reason", TIME));
    }

    @Test
    void editingCannotChangeReferenceOrTypeAndCannotRewriteEarlierTimestamps() {
        VoucherDocument draft = create();
        Voucher renamed = new ReceiptCreator().create(receiptDraft("OTHER", 4_000_000));
        Voucher anotherType = new GeneralCreator().create(receiptDraft("WF001", 4_000_000));

        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> draft.edit(owner, renamed, TIME)),
                () -> assertThrows(IllegalArgumentException.class, () -> draft.edit(owner, anotherType, TIME)),
                () -> assertThrows(IllegalArgumentException.class, () -> draft.submit(owner, TIME.minusSeconds(1))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> draft.cancel(owner, "a".repeat(256), TIME)));
    }

    @Test
    void snapshotsUseValueEqualityRatherThanVoucherObjectIdentity() {
        Voucher first = new ReceiptCreator().create(receiptDraft("WF001", 4_000_000));
        Voucher second = new ReceiptCreator().create(receiptDraft("WF001", 4_000_000));

        assertEquals(VoucherSnapshot.from(first), VoucherSnapshot.from(second));
    }

    private VoucherDocument create() {
        return VoucherDocument.create(UUID.randomUUID(), content(), owner, TIME);
    }
}
