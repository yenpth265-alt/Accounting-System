package vn.ketoan.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.DebtStatement;
import vn.ketoan.domain.OpenItemBook;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.Permission;

/** Historical statements include posted partner lines and allocations effective on the requested date. */
public final class DebtService {

    private final UnitOfWork transactions;

    public DebtService(UnitOfWork transactions) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
    }

    public DebtStatement statement(String accountCode, String partnerCode, LocalDate asOf, long actorId) {
        Objects.requireNonNull(asOf, "Statement date is required");
        return transactions.execute(session -> {
            requireReader(session, actorId);
            Account account = session.accounts().findByCode(Account.normalizeCode(accountCode)).orElseThrow(
                    () -> new IllegalArgumentException("Debt account not found: " + accountCode));
            OpenItemBook.requireDebtAccount(account);
            String partner = Partner.normalizeCode(partnerCode);
            session.partners().findByCode(partner).orElseThrow(
                    () -> new IllegalArgumentException("Partner not found: " + partner));
            return new OpenItemBook(session.accounts().findAll(), session.journal().findAll(),
                    session.allocations().findAll(), session.allocations().findCancellations())
                    .statement(account.getCode(), partner, asOf);
        });
    }

    public List<DebtAllocation> allocations(long actorId) {
        return transactions.execute(session -> {
            requireReader(session, actorId);
            return List.copyOf(session.allocations().findAll());
        });
    }

    public List<AllocationCancellation> cancellations(long actorId) {
        return transactions.execute(session -> {
            requireReader(session, actorId);
            return List.copyOf(session.allocations().findCancellations());
        });
    }

    private void requireReader(AccountingSession session, long actorId) {
        session.users().findById(actorId).orElseThrow(() -> new AccessDeniedException("Unknown user: " + actorId))
                .requirePermission(Permission.VIEW_VOUCHERS);
    }
}
