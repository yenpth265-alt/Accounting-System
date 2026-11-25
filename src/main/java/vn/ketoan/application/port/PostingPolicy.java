package vn.ketoan.application.port;

import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.VoucherDocument;

/** Additional posting rules share the caller's existing transaction and cannot open nested transactions. */
public interface PostingPolicy {

    void beforePost(AccountingSession session, VoucherDocument approved);
    void afterPost(AccountingSession session, JournalEntry entry);
}
