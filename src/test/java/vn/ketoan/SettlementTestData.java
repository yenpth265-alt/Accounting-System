package vn.ketoan;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.service.DebtAllocationService;
import vn.ketoan.application.service.RecognitionService;
import vn.ketoan.application.service.SettlementService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.AllocationRequest;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLine;
import vn.ketoan.domain.JournalLineKey;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.SettlementDraft;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.value.Money;

import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.PostingTestData.posting;
import static vn.ketoan.PostingTestData.validators;
import static vn.ketoan.PostingTestData.workflow;
import static vn.ketoan.VoucherTestData.DATE;
import static vn.ketoan.WorkflowTestData.CHIEF;
import static vn.ketoan.WorkflowTestData.OWNER;

public final class SettlementTestData {

    private SettlementTestData() {
    }

    public static SettlementService settlements(UnitOfWork transactions) {
        return new SettlementService(transactions, validators(), CLOCK);
    }

    public static DebtAllocationService allocations(UnitOfWork transactions) {
        return new DebtAllocationService(transactions, CLOCK);
    }

    public static SettlementDraft receiptInput(String reference, long amount) {
        return new SettlementDraft(reference, DATE, DATE, "Customer repayment", "KH01", "131", "CASH01", Money.of(amount));
    }

    public static JournalEntry sale(UnitOfWork transactions, String reference, long amount) {
        VoucherDocument draft = new RecognitionService(transactions, validators(), CLOCK).createRevenue(
                new RecognitionDraft(reference, DATE, DATE, "Recognized service", "KH01", "131",
                        List.of(new RecognitionItem("5113", Money.of(amount)))), OWNER);
        return postDraft(transactions, draft);
    }

    public static JournalEntry receipt(UnitOfWork transactions, String reference, long amount) {
        return postDraft(transactions, settlements(transactions).createCustomerReceipt(receiptInput(reference, amount), OWNER));
    }

    public static JournalEntry postDraft(UnitOfWork transactions, VoucherDocument draft) {
        VoucherDocument approved = transactions.execute(session -> {
            VoucherWorkflowService workflow = workflow(session);
            VoucherDocument pending = workflow.submit(draft.getId(), draft.getVersion(), OWNER);
            return workflow.approve(pending.getId(), pending.getVersion(), CHIEF);
        });
        return posting(transactions).post(approved.getId(), approved.getVersion(), CHIEF);
    }

    public static JournalLineKey key(JournalEntry entry, String account) {
        JournalLine line = entry.getLines().stream().filter(value -> value.getAccountCode().equals(account))
                .findFirst().orElseThrow();
        return new JournalLineKey(entry.getId(), line.getLineNumber());
    }

    public static AllocationRequest request(JournalEntry charge, JournalEntry settlement, long amount) {
        return request(charge, settlement, "131", amount, DATE);
    }

    public static AllocationRequest request(JournalEntry charge, JournalEntry settlement, String account,
                                             long amount, LocalDate date) {
        return new AllocationRequest(UUID.randomUUID(), key(charge, account), key(settlement, account),
                Money.of(amount), date, "Match a posted payment to its debt");
    }
}
