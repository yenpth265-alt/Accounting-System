package vn.ketoan.infrastructure.memory;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.JournalRepository;
import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.application.port.UserRepository;
import vn.ketoan.application.port.VoucherDocumentRepository;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.User;
import vn.ketoan.domain.VoucherDocument;

/** Private working copies; guarded repository views cannot escape their transaction. */
final class InMemoryAccountingSession implements AccountingSession {

    private final Thread owner = Thread.currentThread();
    private boolean active = true;
    private boolean rollbackOnly;
    private final InMemoryAccountRepository accountData = new InMemoryAccountRepository();
    private final InMemoryPartnerRepository partnerData = new InMemoryPartnerRepository();
    private final InMemoryMoneyAccountRepository fundData = new InMemoryMoneyAccountRepository();
    private final InMemoryAccountingPeriodRepository periodData = new InMemoryAccountingPeriodRepository();
    private final InMemoryUserRepository userData = new InMemoryUserRepository();
    private final InMemoryVoucherDocumentRepository documentData;
    private final InMemoryJournalRepository journalData = new InMemoryJournalRepository();

    InMemoryAccountingSession(InMemoryAccountingState committed) {
        committed.accounts().forEach(accountData::insert);
        committed.partners().forEach(partnerData::insert);
        committed.moneyAccounts().forEach(fundData::insert);
        committed.periods().forEach(periodData::insert);
        committed.users().forEach(userData::insert);
        documentData = new InMemoryVoucherDocumentRepository(committed.documents());
        committed.journal().forEach(journalData::append);
    }

    private void checkScope() {
        if (!active || Thread.currentThread() != owner) {
            throw new IllegalStateException("Repository access must stay inside its transaction and thread");
        }
    }

    private <T> T access(Supplier<T> operation) {
        checkScope();
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            rollbackOnly = true;
            throw failure;
        }
    }

    private void mutate(Runnable operation) {
        access(() -> { operation.run(); return null; });
    }

    void abort() {
        rollbackOnly = true;
    }

    void close() {
        active = false;
    }

    InMemoryAccountingState snapshot() {
        checkScope();
        if (rollbackOnly) {
            throw new IllegalStateException("Transaction is rollback-only after a repository failure");
        }
        return new InMemoryAccountingState(accountData.findAll(), partnerData.findAll(), fundData.findAll(),
                periodData.findAll(), userData.findAll(), documentData.findAll(), journalData.findAll());
    }

    private final AccountRepository accountView = new AccountRepository() {
        @Override
        public Optional<Account> findByCode(String code) {
            return access(() -> accountData.findByCode(code));
        }
        @Override
        public List<Account> findAll() {
            return access(accountData::findAll);
        }
        @Override
        public List<Account> findChildren(String code) {
            return access(() -> accountData.findChildren(code));
        }
        @Override
        public void insert(Account value) {
            mutate(() -> accountData.insert(value));
        }
        @Override
        public void update(Account value) {
            mutate(() -> accountData.update(value));
        }
    };

    private final PartnerRepository partnerView = new PartnerRepository() {
        @Override
        public Optional<Partner> findByCode(String code) {
            return access(() -> partnerData.findByCode(code));
        }
        @Override
        public List<Partner> findAll() {
            return access(partnerData::findAll);
        }
        @Override
        public void insert(Partner value) {
            mutate(() -> partnerData.insert(value));
        }
        @Override
        public void update(Partner value) {
            mutate(() -> partnerData.update(value));
        }
    };

    private final MoneyAccountRepository fundView = new MoneyAccountRepository() {
        @Override
        public Optional<MoneyAccount> findByCode(String code) {
            return access(() -> fundData.findByCode(code));
        }
        @Override
        public List<MoneyAccount> findAll() {
            return access(fundData::findAll);
        }
        @Override
        public void insert(MoneyAccount value) {
            mutate(() -> fundData.insert(value));
        }
        @Override
        public void update(MoneyAccount value) {
            mutate(() -> fundData.update(value));
        }
    };

    private final AccountingPeriodRepository periodView = new AccountingPeriodRepository() {
        @Override public Optional<AccountingPeriod> findByMonth(YearMonth month) {
            return access(() -> periodData.findByMonth(month));
        }
        @Override
        public List<AccountingPeriod> findAll() {
            return access(periodData::findAll);
        }
        @Override
        public void insert(AccountingPeriod value) {
            mutate(() -> periodData.insert(value));
        }
        @Override
        public void update(AccountingPeriod value) {
            mutate(() -> periodData.update(value));
        }
    };

    private final UserRepository userView = new UserRepository() {
        @Override
        public Optional<User> findById(long id) {
            return access(() -> userData.findById(id));
        }
        @Override
        public List<User> findAll() {
            return access(userData::findAll);
        }
        @Override
        public void insert(User value) {
            mutate(() -> userData.insert(value));
        }
        @Override
        public void update(User value) {
            mutate(() -> userData.update(value));
        }
    };

    private final VoucherDocumentRepository documentView = new VoucherDocumentRepository() {
        @Override
        public Optional<VoucherDocument> findById(UUID id) {
            return access(() -> documentData.findById(id));
        }
        @Override
        public List<VoucherDocument> findAll() {
            return access(documentData::findAll);
        }
        @Override
        public void insert(VoucherDocument value) {
            mutate(() -> documentData.insert(value));
        }
        @Override public void update(VoucherDocument value, long expectedVersion) {
            mutate(() -> documentData.update(value, expectedVersion));
        }
    };

    private final JournalRepository journalView = new JournalRepository() {
        @Override
        public Optional<JournalEntry> findById(UUID id) {
            return access(() -> journalData.findById(id));
        }
        @Override public Optional<JournalEntry> findBySourceDocumentId(UUID id) {
            return access(() -> journalData.findBySourceDocumentId(id));
        }
        @Override
        public List<JournalEntry> findAll() {
            return access(journalData::findAll);
        }
        @Override
        public void append(JournalEntry value) {
            mutate(() -> journalData.append(value));
        }
    };

    @Override
    public AccountRepository accounts() {
        checkScope();
        return accountView;
    }
    @Override
    public PartnerRepository partners() {
        checkScope();
        return partnerView;
    }
    @Override
    public MoneyAccountRepository moneyAccounts() {
        checkScope();
        return fundView;
    }
    @Override
    public AccountingPeriodRepository periods() {
        checkScope();
        return periodView;
    }
    @Override
    public UserRepository users() {
        checkScope();
        return userView;
    }
    @Override
    public VoucherDocumentRepository documents() {
        checkScope();
        return documentView;
    }
    @Override
    public JournalRepository journal() {
        checkScope();
        return journalView;
    }
}
