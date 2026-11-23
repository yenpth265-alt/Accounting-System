package vn.ketoan.application.port;

/** Repositories are scoped to one UnitOfWork callback and its calling thread. */
public interface AccountingSession {

    AccountRepository accounts();

    PartnerRepository partners();

    MoneyAccountRepository moneyAccounts();

    AccountingPeriodRepository periods();

    UserRepository users();

    VoucherDocumentRepository documents();

    JournalRepository journal();
}
