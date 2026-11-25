package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import vn.ketoan.application.port.AllocationUseCases;
import vn.ketoan.application.port.RecognitionUseCases;
import vn.ketoan.application.port.SettlementUseCases;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.application.service.DebtAllocationService;
import vn.ketoan.application.service.DebtService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.application.service.PostingService;
import vn.ketoan.application.service.RecognitionService;
import vn.ketoan.application.service.SettlementService;
import vn.ketoan.application.service.VoucherValidationService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.AllocationRequest;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.DebtStatement;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLine;
import vn.ketoan.domain.JournalLineKey;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.MoneyAccountKind;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.SettlementDraft;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

public final class SettlementDemo {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 25);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-25T03:00:00Z"), ZoneOffset.UTC);
    private static final VoucherValidatorFactory VALIDATORS = session -> new VoucherValidationService(
            session.accounts(), session.partners(), session.moneyAccounts(), session.periods());

    private SettlementDemo() {
    }

    public static void run(PrintStream output) {
        UnitOfWork transactions = new InMemoryUnitOfWork();
        seed(transactions);
        RecognitionUseCases recognition = new RecognitionService(transactions, VALIDATORS, CLOCK);
        SettlementUseCases settlements = new SettlementService(transactions, VALIDATORS, CLOCK);
        AllocationUseCases allocations = new DebtAllocationService(transactions, CLOCK);
        DebtService debts = new DebtService(transactions);
        LedgerService ledger = new LedgerService(transactions);

        output.println("SETTLEMENT AND OPEN ITEM DEMO");
        JournalEntry sale = post(transactions, recognition.createRevenue(recognition("DT001", "KH01", "131",
                "5113", 10_000_000), 1));
        JournalEntry cost = post(transactions, recognition.createExpense(recognition("CP001", "NCC01", "331",
                "6427", 3_000_000), 1));
        VoucherDocument receipt = settlements.createCustomerReceipt(settlement("PT001", "KH01", "131", 4_000_000), 1);
        VoucherDocument payment = settlements.createSupplierPayment(settlement("PC001", "NCC01", "331", 1_000_000), 1);
        output.println("RECEIPT/PAYMENT DRAFTS: cash=" + ledger.netBalance("1111", DATE, 4));
        JournalEntry collection = post(transactions, receipt);
        JournalEntry disbursement = post(transactions, payment);
        output.println("AFTER POSTING: cash=" + ledger.netBalance("1111", DATE, 4)
                + "; revenue=" + ledger.netBalance("5113", DATE, 4).negate()
                + "; expense=" + ledger.netBalance("6427", DATE, 4));
        printStatement(output, "CUSTOMER BEFORE ALLOCATION", debts.statement("131", "KH01", DATE, 4));
        printStatement(output, "SUPPLIER BEFORE ALLOCATION", debts.statement("331", "NCC01", DATE, 4));

        AllocationRequest customer = request(sale, collection, "131", 4_000_000);
        allocations.allocate(customer, 2);
        allocations.allocate(request(cost, disbursement, "331", 1_000_000), 2);
        allocations.allocate(customer, 2);
        output.println("RETRY: allocation records=" + debts.allocations(4).size());
        printStatement(output, "CUSTOMER AFTER ALLOCATION", debts.statement("131", "KH01", DATE, 4));
        printStatement(output, "SUPPLIER AFTER ALLOCATION", debts.statement("331", "NCC01", DATE, 4));
        try {
            allocations.allocate(request(sale, collection, "131", 1), 2);
        } catch (IllegalArgumentException expected) {
            output.println("OVER-ALLOCATION REJECTED: " + expected.getMessage());
        }

        JournalEntry excess = post(transactions, settlements.createCustomerReceipt(
                settlement("PT002", "KH01", "131", 8_000_000), 1));
        allocations.allocate(request(sale, excess, "131", 6_000_000), 2);
        printStatement(output, "CUSTOMER EXCESS/ADVANCE", debts.statement("131", "KH01", DATE, 4));

        LocalDate nextDay = DATE.plusDays(1);
        Clock nextClock = Clock.fixed(CLOCK.instant().plusSeconds(86_400), ZoneOffset.UTC);
        new DebtAllocationService(transactions, nextClock).cancel(
                customer.id(), nextDay, "Reopen the matching for review", 2);
        printStatement(output, "HISTORICAL BEFORE CANCELLATION", debts.statement("131", "KH01", DATE, 4));
        printStatement(output, "AFTER CANCELLATION", debts.statement("131", "KH01", nextDay, 4));
        output.println("FINAL LEDGER: cash=" + ledger.netBalance("1111", nextDay, 4)
                + "; customer net receivable=" + ledger.partnerNetBalance("131", "KH01", nextDay, 4)
                + "; journal entries=" + ledger.entries(4).size());
        output.println("Allocation/cancellation keeps all original journal rows and audit records.");
        output.println("Storage is temporary memory; restart resets the demo. MySQL is not connected yet.");
    }

    private static void seed(UnitOfWork transactions) {
        transactions.execute(session -> {
            session.accounts().insert(account("1111", AccountType.ASSET, AccountTracking.MONEY_ACCOUNT));
            session.accounts().insert(account("131", AccountType.ASSET, AccountTracking.PARTNER));
            session.accounts().insert(account("331", AccountType.LIABILITY, AccountTracking.PARTNER));
            session.accounts().insert(account("5113", AccountType.REVENUE, AccountTracking.NONE));
            session.accounts().insert(account("6427", AccountType.EXPENSE, AccountTracking.NONE));
            session.moneyAccounts().insert(new MoneyAccount("CASH01", "Cash fund", "1111", MoneyAccountKind.CASH));
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
        BalanceSide side = type == AccountType.ASSET || type == AccountType.EXPENSE ? BalanceSide.DEBIT : BalanceSide.CREDIT;
        return Account.posting(code, "Account " + code, type, side, null, tracking);
    }

    private static RecognitionDraft recognition(String reference, String partner, String debtAccount,
                                                String mainAccount, long amount) {
        return new RecognitionDraft(reference, DATE, DATE, "Recognized service", partner, debtAccount,
                List.of(new RecognitionItem(mainAccount, Money.of(amount))));
    }

    private static SettlementDraft settlement(String reference, String partner, String debtAccount, long amount) {
        return new SettlementDraft(reference, DATE, DATE, "Debt settlement", partner, debtAccount, "CASH01", Money.of(amount));
    }

    private static JournalEntry post(UnitOfWork transactions, VoucherDocument draft) {
        VoucherDocument approved = transactions.execute(session -> {
            VoucherWorkflowService workflow = new VoucherWorkflowService(
                    session.users(), session.documents(), VALIDATORS.create(session), CLOCK);
            VoucherDocument pending = workflow.submit(draft.getId(), draft.getVersion(), 1);
            return workflow.approve(pending.getId(), pending.getVersion(), 2);
        });
        return new PostingService(transactions, VALIDATORS, CLOCK).post(approved.getId(), approved.getVersion(), 2);
    }

    private static AllocationRequest request(JournalEntry charge, JournalEntry settlement, String account, long amount) {
        return new AllocationRequest(UUID.randomUUID(), key(charge, account), key(settlement, account),
                Money.of(amount), DATE, "Match posted debt and settlement");
    }

    private static JournalLineKey key(JournalEntry entry, String account) {
        JournalLine line = entry.getLines().stream().filter(value -> value.getAccountCode().equals(account))
                .findFirst().orElseThrow();
        return new JournalLineKey(entry.getId(), line.getLineNumber());
    }

    private static void printStatement(PrintStream output, String label, DebtStatement statement) {
        output.printf("%s: unsettled debt=%s; unallocated settlement=%s; net debt=%s%n",
                label, statement.unsettledDebt(), statement.unallocatedSettlements(), statement.netDebt());
        statement.items().forEach(item -> output.printf("  %s | %s | original=%s | allocated=%s | remaining=%s%n",
                item.reference(), item.kind(), item.original(), item.allocated(), item.remaining()));
    }
}
