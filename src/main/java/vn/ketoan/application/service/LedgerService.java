package vn.ketoan.application.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLine;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.value.Money;

/** Ledger-only reads. Net amounts use debit minus credit, including for inactive catalogs. */
public final class LedgerService {

    private final UnitOfWork transactions;

    public LedgerService(UnitOfWork transactions) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
    }

    public List<JournalEntry> entries(long actorId) {
        return transactions.execute(session -> {
            requireReader(session, actorId);
            return List.copyOf(session.journal().findAll());
        });
    }

    public Money netBalance(String accountCode, LocalDate asOf, long actorId) {
        Objects.requireNonNull(asOf, "Balance date is required");
        return transactions.execute(session -> {
            requireReader(session, actorId);
            Account account = requireAccount(session, accountCode);
            return sum(session, account.getCode(), null, asOf);
        });
    }

    public Money partnerNetBalance(String accountCode, String partnerCode, LocalDate asOf, long actorId) {
        Objects.requireNonNull(asOf, "Balance date is required");
        return transactions.execute(session -> {
            requireReader(session, actorId);
            Account account = requireAccount(session, accountCode);
            if (account.getTracking() != AccountTracking.PARTNER) {
                throw new IllegalArgumentException("Account does not track partner balances: " + account.getCode());
            }
            String partner = Partner.normalizeCode(partnerCode);
            session.partners().findByCode(partner).orElseThrow(
                    () -> new IllegalArgumentException("Partner not found: " + partner));
            return sum(session, account.getCode(), partner, asOf);
        });
    }

    private Account requireAccount(AccountingSession session, String code) {
        String normalized = Account.normalizeCode(code);
        Account account = session.accounts().findByCode(normalized).orElseThrow(
                () -> new IllegalArgumentException("Account not found: " + normalized));
        if (!account.isPostingAllowed()) {
            throw new IllegalArgumentException("Direct balance lookup requires a posting account");
        }
        return account;
    }

    private void requireReader(AccountingSession session, long actorId) {
        session.users().findById(actorId).orElseThrow(
                () -> new AccessDeniedException("Unknown user: " + actorId))
                .requirePermission(Permission.VIEW_VOUCHERS);
    }

    private Money sum(AccountingSession session, String accountCode, String partnerCode, LocalDate asOf) {
        return session.journal().findAll().stream()
                .filter(entry -> !entry.getPostingDate().isAfter(asOf))
                .flatMap(entry -> entry.getLines().stream())
                .filter(line -> line.getAccountCode().equals(accountCode))
                .filter(line -> partnerCode == null || line.getPartnerCode().filter(partnerCode::equals).isPresent())
                .map(this::signedAmount).reduce(Money.ZERO, Money::add);
    }

    private Money signedAmount(JournalLine line) {
        return line.getDebit().subtract(line.getCredit());
    }
}
