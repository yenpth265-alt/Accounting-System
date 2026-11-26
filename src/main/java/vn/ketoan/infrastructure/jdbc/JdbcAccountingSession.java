package vn.ketoan.infrastructure.jdbc;

import java.sql.Connection;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.DebtAllocationRepository;
import vn.ketoan.application.port.CorrectionRepository;
import vn.ketoan.application.port.ClosingRepository;
import vn.ketoan.application.port.CashFlowClassificationRepository;
import vn.ketoan.application.port.ReportVersionRepository;
import vn.ketoan.infrastructure.report.XmlReportDocumentCodec;
import vn.ketoan.application.port.JournalRepository;
import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.application.port.PrepaidAllocationRepository;
import vn.ketoan.application.port.PrepaidPlanRepository;
import vn.ketoan.application.port.UserRepository;
import vn.ketoan.application.port.VoucherDocumentRepository;

final class JdbcAccountingSession implements AccountingSession {

    private final JdbcContext context;
    private final AccountRepository accounts;
    private final PartnerRepository partners;
    private final MoneyAccountRepository funds;
    private final AccountingPeriodRepository periods;
    private final UserRepository users;
    private final VoucherDocumentRepository documents;
    private final JournalRepository journal;
    private final DebtAllocationRepository allocations;
    private final CorrectionRepository corrections;
    private final PrepaidPlanRepository prepaidPlans;
    private final PrepaidAllocationRepository prepaidAllocations;
    private final ClosingRepository closings;
    private final CashFlowClassificationRepository cashFlowClassifications;
    private final ReportVersionRepository reportVersions;

    JdbcAccountingSession(Connection connection) {
        context = new JdbcContext(connection);
        accounts = new JdbcAccountRepository(context);
        partners = new JdbcPartnerRepository(context);
        funds = new JdbcMoneyAccountRepository(context);
        periods = new JdbcAccountingPeriodRepository(context);
        users = new JdbcUserRepository(context);
        documents = new JdbcVoucherDocumentRepository(context);
        journal = new JdbcJournalRepository(context);
        allocations = new JdbcDebtAllocationRepository(context);
        corrections = new JdbcCorrectionRepository(context);
        prepaidPlans = new JdbcPrepaidPlanRepository(context);
        prepaidAllocations = new JdbcPrepaidAllocationRepository(context);
        closings = new JdbcClosingRepository(context);
        cashFlowClassifications = new JdbcCashFlowClassificationRepository(context);
        reportVersions = new JdbcReportVersionRepository(context, new XmlReportDocumentCodec());
    }

    void abort() { context.abort(); }
    void requireCommittable() { context.requireCommittable(); }
    void close() { context.close(); }

    @Override public AccountRepository accounts() { context.checkScope(); return accounts; }
    @Override public PartnerRepository partners() { context.checkScope(); return partners; }
    @Override public MoneyAccountRepository moneyAccounts() { context.checkScope(); return funds; }
    @Override public AccountingPeriodRepository periods() { context.checkScope(); return periods; }
    @Override public UserRepository users() { context.checkScope(); return users; }
    @Override public VoucherDocumentRepository documents() { context.checkScope(); return documents; }
    @Override public JournalRepository journal() { context.checkScope(); return journal; }
    @Override public DebtAllocationRepository allocations() { context.checkScope(); return allocations; }
    @Override public CorrectionRepository corrections() { context.checkScope(); return corrections; }
    @Override public PrepaidPlanRepository prepaidPlans() { context.checkScope(); return prepaidPlans; }
    @Override public PrepaidAllocationRepository prepaidAllocations() { context.checkScope(); return prepaidAllocations; }
    @Override public ClosingRepository closings() { context.checkScope(); return closings; }
    @Override public CashFlowClassificationRepository cashFlowClassifications() { context.checkScope(); return cashFlowClassifications; }
    @Override public ReportVersionRepository reportVersions() { context.checkScope(); return reportVersions; }
}
