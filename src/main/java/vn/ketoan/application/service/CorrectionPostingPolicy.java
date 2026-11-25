package vn.ketoan.application.service;

import java.util.ArrayList;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.domain.CorrectionKind;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.OpenItemBook;
import vn.ketoan.domain.VoucherDocument;

public final class CorrectionPostingPolicy implements PostingPolicy {

    @Override
    public void beforePost(AccountingSession session, VoucherDocument approved) {
        if (session.corrections().findByTargetDocumentId(approved.getId()).isPresent()) {
            AccountingBooks.prepaid(session);
        }
    }

    @Override
    public void afterPost(AccountingSession session, JournalEntry entry) {
        var correction = session.corrections().findByTargetDocumentId(entry.getSourceDocumentId());
        if (correction.isEmpty() || correction.orElseThrow().kind() != CorrectionKind.REVERSAL) { return; }
        var matching = AccountingBooks.corrections(session).matching(correction.orElseThrow());
        var candidate = new ArrayList<DebtAllocation>(session.allocations().findAll());
        candidate.addAll(matching);
        try {
            new OpenItemBook(session.accounts().findAll(), session.journal().findAll(), candidate,
                    session.allocations().findCancellations());
        } catch (IllegalArgumentException failure) {
            throw new IllegalStateException("Cancel existing source allocations effective on or before the reversal date before posting", failure);
        }
        matching.forEach(session.allocations()::append);
    }
}
