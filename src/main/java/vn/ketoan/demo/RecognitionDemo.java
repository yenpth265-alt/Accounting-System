package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.RecognitionUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.application.service.AccountService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.application.service.PostingService;
import vn.ketoan.application.service.RecognitionService;
import vn.ketoan.application.service.VoucherValidationService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

public final class RecognitionDemo {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 25);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);
    private static final VoucherValidatorFactory VALIDATORS = session -> new VoucherValidationService(
            session.accounts(), session.partners(), session.moneyAccounts(), session.periods());

    private RecognitionDemo() {
    }

    public static void run(PrintStream output) {
        UnitOfWork transactions = new InMemoryUnitOfWork();
        seed(transactions);
        RecognitionUseCases recognition = new RecognitionService(transactions, VALIDATORS, CLOCK);
        PostingService posting = new PostingService(transactions, VALIDATORS, CLOCK);
        LedgerService ledger = new LedgerService(transactions);

        output.println("REVENUE AND EXPENSE RECOGNITION DEMO");
        output.println("Enter a partner, debt account and recognition items; balancing lines are generated.");
        VoucherDocument sale = recognition.createRevenue(input("DT001", "KH01", "131",
                item("5113", 10_000_000)), 1);
        VoucherDocument expense = recognition.createExpense(input("CP001", "NCC01", "331",
                item("6427", 3_000_000)), 1);
        printDocument(output, sale);
        printDocument(output, expense);
        output.println("DRAFT: journal entries=" + ledger.entries(4).size());
        printBalances(output, ledger);

        sale = approve(transactions, sale);
        expense = approve(transactions, expense);
        output.println("APPROVED: journal entries=" + ledger.entries(4).size());
        posting.post(sale.getId(), sale.getVersion(), 2);
        posting.post(expense.getId(), expense.getVersion(), 2);
        output.println("POSTED: journal entries=" + ledger.entries(4).size());
        printBalances(output, ledger);
        output.println("Cash stays at zero. Receipts and payments will settle debts separately.");

        output.println("OPTIONAL TAX COMPONENTS: amounts are entered explicitly; no tax rate is assumed.");
        printDocument(output, recognition.createRevenue(input("DT002", "KH01", "131",
                item("5113", 10_000_000), item("33311", 1_000_000)), 1));
        printDocument(output, recognition.createExpense(input("CP002", "NCC01", "331",
                item("6427", 3_000_000), item("1331", 300_000)), 1));
        output.println("These two tax examples remain DRAFT and do not change the ledger above.");

        int countBefore = transactions.execute(session -> session.documents().findAll().size());
        try {
            recognition.createRevenue(input("INVALID", "NCC01", "131", item("5113", 100)), 1);
        } catch (IllegalStateException expected) {
            output.println("REJECTED supplier as customer: " + expected.getMessage());
        }
        int countAfter = transactions.execute(session -> session.documents().findAll().size());
        output.println("AFTER REJECTION: documents=" + countAfter + "; unchanged=" + (countBefore == countAfter));
        output.println("Storage is temporary memory; restart resets the demo. MySQL is not connected yet.");
    }

    private static void seed(UnitOfWork transactions) {
        transactions.execute(session -> {
            AccountService accounts = new AccountService(session.accounts());
            accounts.add(account("1111", AccountType.ASSET, AccountTracking.MONEY_ACCOUNT));
            accounts.add(account("131", AccountType.ASSET, AccountTracking.PARTNER));
            accounts.add(account("331", AccountType.LIABILITY, AccountTracking.PARTNER));
            accounts.add(account("5113", AccountType.REVENUE, AccountTracking.NONE));
            accounts.add(account("6427", AccountType.EXPENSE, AccountTracking.NONE));
            accounts.add(account("33311", AccountType.LIABILITY, AccountTracking.NONE));
            accounts.add(account("1331", AccountType.ASSET, AccountTracking.NONE));
            session.partners().insert(new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER)));
            session.partners().insert(new Partner("NCC01", "Supplier B", Set.of(PartnerRole.SUPPLIER)));
            session.periods().insert(AccountingPeriod.open(YearMonth.from(DATE)));
            session.users().insert(new User(1, "ketoan", "Accountant", Set.of(UserRole.ACCOUNTANT)));
            session.users().insert(new User(2, "truong", "Chief accountant", Set.of(UserRole.CHIEF)));
            session.users().insert(new User(4, "viewer", "Viewer", Set.of(UserRole.VIEWER)));
            return null;
        });
    }

    private static Account account(String code, AccountType type, AccountTracking tracking) {
        BalanceSide normalBalance = type == AccountType.ASSET || type == AccountType.EXPENSE
                ? BalanceSide.DEBIT : BalanceSide.CREDIT;
        return Account.posting(code, "Account " + code, type, normalBalance, null, tracking);
    }

    private static RecognitionDraft input(String reference, String partner, String debtAccount,
                                          RecognitionItem... items) {
        return new RecognitionDraft(reference, DATE, DATE, "Recognized service: " + reference,
                partner, debtAccount, List.of(items));
    }

    private static RecognitionItem item(String account, long amount) {
        return new RecognitionItem(account, Money.of(amount));
    }

    private static VoucherDocument approve(UnitOfWork transactions, VoucherDocument draft) {
        return transactions.execute(session -> {
            VoucherWorkflowService workflow = workflow(session);
            VoucherDocument pending = workflow.submit(draft.getId(), draft.getVersion(), 1);
            return workflow.approve(pending.getId(), pending.getVersion(), 2);
        });
    }

    private static VoucherWorkflowService workflow(AccountingSession session) {
        return new VoucherWorkflowService(session.users(), session.documents(), VALIDATORS.create(session), CLOCK);
    }

    private static void printDocument(PrintStream output, VoucherDocument document) {
        output.printf("%s %s: %s; total=%s%n", document.getContent().getType(),
                document.getContent().getReference(), document.getStatus(), document.getContent().getTotalDebit());
        document.getContent().getLines().forEach(line -> output.printf(
                "  account=%s | debit=%s | credit=%s | partner=%s%n",
                line.getAccountCode(), line.getDebit(), line.getCredit(), line.getPartnerCode().orElse("-")));
    }

    private static void printBalances(PrintStream output, LedgerService ledger) {
        output.printf("BALANCES: cash=%s; customer A receivable=%s; supplier B payable=%s%n",
                ledger.netBalance("1111", DATE, 4), ledger.partnerNetBalance("131", "KH01", DATE, 4),
                ledger.partnerNetBalance("331", "NCC01", DATE, 4).negate());
        output.printf("RECOGNIZED: revenue=%s; expense=%s%n",
                ledger.netBalance("5113", DATE, 4).negate(), ledger.netBalance("6427", DATE, 4));
    }
}
