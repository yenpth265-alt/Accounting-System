package vn.ketoan.application.service;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.ClosingAction;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.VoucherDocument;

public final class ClosingPostingPolicy implements PostingPolicy {
    @Override public void beforePost(AccountingSession session, VoucherDocument approved) {
        var closing = session.closings().findByDocumentId(approved.getId());
        if (closing.isPresent()) {
            boolean reviewed = session.closings().findEvents().stream().anyMatch(event -> event.batchId().equals(closing.orElseThrow().id())
                    && event.action() == ClosingAction.APPROVED);
            if (!reviewed) { throw new IllegalStateException("Review the closing batch before posting"); }
        } else if (approved.getContent().getLines().stream().anyMatch(line -> session.accounts().findByCode(line.getAccountCode())
                .orElseThrow().getType() == AccountType.RESULT)) {
            throw new IllegalStateException("Use the closing workflow to post to a result account");
        }
    }
    @Override public void afterPost(AccountingSession session, JournalEntry entry) { }
}
