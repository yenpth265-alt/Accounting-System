package vn.ketoan.infrastructure.memory;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import vn.ketoan.application.model.AccountingSnapshot;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.DebtAllocationRepository;
import vn.ketoan.application.port.CorrectionRepository;
import vn.ketoan.application.port.ClosingRepository;
import vn.ketoan.application.port.CashFlowClassificationRepository;
import vn.ketoan.application.port.ReportVersionRepository;
import vn.ketoan.application.port.JournalRepository;
import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.application.port.PrepaidAllocationRepository;
import vn.ketoan.application.port.PrepaidPlanRepository;
import vn.ketoan.application.port.UserRepository;
import vn.ketoan.application.port.VoucherDocumentRepository;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.CorrectionRecord;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.ClosingEvent;
import vn.ketoan.domain.report.CashFlowClassification;
import vn.ketoan.domain.report.ReportVersion;
import vn.ketoan.domain.report.ReportPublication;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PrepaidAllocationRecord;
import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidPlanCancellation;
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
    private final InMemoryDebtAllocationRepository allocationData = new InMemoryDebtAllocationRepository();
    private final InMemoryCorrectionRepository correctionData = new InMemoryCorrectionRepository();
    private final InMemoryPrepaidPlanRepository prepaidPlanData = new InMemoryPrepaidPlanRepository();
    private final InMemoryPrepaidAllocationRepository prepaidAllocationData = new InMemoryPrepaidAllocationRepository();
    private final InMemoryClosingRepository closingData = new InMemoryClosingRepository();
    private final InMemoryCashFlowClassificationRepository cashFlowData = new InMemoryCashFlowClassificationRepository();
    private final InMemoryReportVersionRepository reportVersionData = new InMemoryReportVersionRepository();

    InMemoryAccountingSession(AccountingSnapshot committed) {
        committed.accounts().forEach(accountData::insert);
        committed.partners().forEach(partnerData::insert);
        committed.moneyAccounts().forEach(fundData::insert);
        committed.periods().forEach(periodData::insert);
        committed.users().forEach(userData::insert);
        documentData = new InMemoryVoucherDocumentRepository(committed.documents());
        committed.journal().forEach(journalData::append);
        committed.allocations().forEach(allocationData::append);
        committed.cancellations().forEach(allocationData::appendCancellation);
        committed.corrections().forEach(correctionData::append);
        committed.prepaidPlans().forEach(prepaidPlanData::append);
        committed.prepaidCancellations().forEach(prepaidPlanData::appendCancellation);
        committed.prepaidAllocations().forEach(prepaidAllocationData::append);
        committed.closingBatches().forEach(closingData::append);
        committed.closingEvents().forEach(closingData::appendEvent);
        committed.cashFlowClassifications().forEach(cashFlowData::append);
        committed.reportVersions().forEach(reportVersionData::append);
        committed.reportPublications().forEach(reportVersionData::appendPublication);
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

    AccountingSnapshot snapshot() {
        checkScope();
        if (rollbackOnly) {
            throw new IllegalStateException("Transaction is rollback-only after a repository failure");
        }
        return new AccountingSnapshot(accountData.findAll(), partnerData.findAll(), fundData.findAll(),
                periodData.findAll(), userData.findAll(), documentData.findAll(), journalData.findAll(),
                allocationData.findAll(), allocationData.findCancellations(), correctionData.findAll(),
                prepaidPlanData.findAll(), prepaidPlanData.findCancellations(), prepaidAllocationData.findAll(),
                closingData.findAll(), closingData.findEvents(), cashFlowData.findAll(), reportVersionData.findAll(), reportVersionData.findPublications());
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

    private final DebtAllocationRepository allocationView = new DebtAllocationRepository() {
        @Override
        public Optional<DebtAllocation> findById(UUID id) {
            return access(() -> allocationData.findById(id));
        }
        @Override
        public List<DebtAllocation> findAll() {
            return access(allocationData::findAll);
        }
        @Override
        public Optional<AllocationCancellation> findCancellation(UUID id) {
            return access(() -> allocationData.findCancellation(id));
        }
        @Override
        public List<AllocationCancellation> findCancellations() {
            return access(allocationData::findCancellations);
        }
        @Override
        public void append(DebtAllocation allocation) {
            mutate(() -> allocationData.append(allocation));
        }
        @Override
        public void appendCancellation(AllocationCancellation cancellation) {
            mutate(() -> allocationData.appendCancellation(cancellation));
        }
    };

    private final CorrectionRepository correctionView = new CorrectionRepository() {
        @Override public Optional<CorrectionRecord> findById(UUID id) { return access(() -> correctionData.findById(id)); }
        @Override public Optional<CorrectionRecord> findByTargetDocumentId(UUID id) { return access(() -> correctionData.findByTargetDocumentId(id)); }
        @Override public List<CorrectionRecord> findAll() { return access(correctionData::findAll); }
        @Override public void append(CorrectionRecord record) { mutate(() -> correctionData.append(record)); }
    };

    private final PrepaidPlanRepository prepaidPlanView = new PrepaidPlanRepository() {
        @Override public Optional<PrepaidExpensePlan> findById(UUID id) { return access(() -> prepaidPlanData.findById(id)); }
        @Override public List<PrepaidExpensePlan> findAll() { return access(prepaidPlanData::findAll); }
        @Override public void append(PrepaidExpensePlan plan) { mutate(() -> prepaidPlanData.append(plan)); }
        @Override public Optional<PrepaidPlanCancellation> findCancellation(UUID id) { return access(() -> prepaidPlanData.findCancellation(id)); }
        @Override public List<PrepaidPlanCancellation> findCancellations() { return access(prepaidPlanData::findCancellations); }
        @Override public void appendCancellation(PrepaidPlanCancellation value) { mutate(() -> prepaidPlanData.appendCancellation(value)); }
    };

    private final PrepaidAllocationRepository prepaidAllocationView = new PrepaidAllocationRepository() {
        @Override public Optional<PrepaidAllocationRecord> findById(UUID id) { return access(() -> prepaidAllocationData.findById(id)); }
        @Override public Optional<PrepaidAllocationRecord> findByTargetDocumentId(UUID id) { return access(() -> prepaidAllocationData.findByTargetDocumentId(id)); }
        @Override public List<PrepaidAllocationRecord> findAll() { return access(prepaidAllocationData::findAll); }
        @Override public void append(PrepaidAllocationRecord value) { mutate(() -> prepaidAllocationData.append(value)); }
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

    @Override
    public DebtAllocationRepository allocations() {
        checkScope();
        return allocationView;
    }

    @Override public CorrectionRepository corrections() { checkScope(); return correctionView; }
    @Override public PrepaidPlanRepository prepaidPlans() { checkScope(); return prepaidPlanView; }
    @Override public PrepaidAllocationRepository prepaidAllocations() { checkScope(); return prepaidAllocationView; }

    private final ClosingRepository closingView = new ClosingRepository() {
        @Override public Optional<ClosingBatch> findById(UUID id) { return access(() -> closingData.findById(id)); }
        @Override public Optional<ClosingBatch> findByDocumentId(UUID id) { return access(() -> closingData.findByDocumentId(id)); }
        @Override public List<ClosingBatch> findAll() { return access(closingData::findAll); }
        @Override public void append(ClosingBatch batch) { mutate(() -> closingData.append(batch)); }
        @Override public List<ClosingEvent> findEvents() { return access(closingData::findEvents); }
        @Override public void appendEvent(ClosingEvent event) { mutate(() -> closingData.appendEvent(event)); }
    };
    @Override public ClosingRepository closings() { checkScope(); return closingView; }
    private final CashFlowClassificationRepository cashFlowView = new CashFlowClassificationRepository() {
        @Override public Optional<CashFlowClassification> findById(UUID id) { return access(() -> cashFlowData.findById(id)); }
        @Override public List<CashFlowClassification> findAll() { return access(cashFlowData::findAll); }
        @Override public void append(CashFlowClassification value) { mutate(() -> cashFlowData.append(value)); }
    };
    private final ReportVersionRepository reportVersionView = new ReportVersionRepository() {
        @Override public Optional<ReportVersion> findById(UUID id) { return access(() -> reportVersionData.findById(id)); }
        @Override public List<ReportVersion> findAll() { return access(reportVersionData::findAll); }
        @Override public void append(ReportVersion value) { mutate(() -> reportVersionData.append(value)); }
        @Override public List<ReportPublication> findPublications() { return access(reportVersionData::findPublications); }
        @Override public void appendPublication(ReportPublication value) { mutate(() -> reportVersionData.appendPublication(value)); }
    };
    @Override public CashFlowClassificationRepository cashFlowClassifications() { checkScope(); return cashFlowView; }
    @Override public ReportVersionRepository reportVersions() { checkScope(); return reportVersionView; }
}
