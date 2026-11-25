package vn.ketoan.application.service;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.VoucherDocument;

public final class PrepaidPostingPolicy implements PostingPolicy {

    @Override
    public void beforePost(AccountingSession session, VoucherDocument approved) {
        if (session.prepaidAllocations().findByTargetDocumentId(approved.getId()).isPresent()) {
            AccountingBooks.prepaid(session).requirePosting(approved.getId());
        }
    }

    @Override
    public void afterPost(AccountingSession session, JournalEntry entry) {
        if (session.prepaidAllocations().findByTargetDocumentId(entry.getSourceDocumentId()).isPresent()) {
            AccountingBooks.prepaid(session);
        }
    }
}
