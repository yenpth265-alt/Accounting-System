package vn.ketoan.application.service;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.CorrectionBook;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.PrepaidBook;
import vn.ketoan.domain.User;

final class AccountingBooks {

    private AccountingBooks() {
    }

    static User actor(AccountingSession session, long id, Permission permission) {
        User actor = session.users().findById(id).orElseThrow(() -> new AccessDeniedException("Unknown user: " + id));
        actor.requirePermission(permission);
        return actor;
    }

    static CorrectionBook corrections(AccountingSession session) {
        return new CorrectionBook(session.accounts().findAll(), session.documents().findAll(),
                session.journal().findAll(), session.corrections().findAll());
    }

    static PrepaidBook prepaid(AccountingSession session) {
        return new PrepaidBook(session.accounts().findAll(), session.documents().findAll(), session.journal().findAll(),
                session.prepaidPlans().findAll(), session.prepaidPlans().findCancellations(),
                session.prepaidAllocations().findAll(), corrections(session));
    }
}
