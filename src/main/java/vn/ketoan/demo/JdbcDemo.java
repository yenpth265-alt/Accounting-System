package vn.ketoan.demo;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import vn.ketoan.application.model.AccountingSnapshot;
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
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RecognitionDraft;
import vn.ketoan.domain.RecognitionItem;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.SettlementDraft;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherSnapshot;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.value.Money;

/** Explicit persistent sample. Repeating the same sample reuses its documents and allocation. */
public final class JdbcDemo {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 25);
    private static final VoucherValidatorFactory VALIDATORS = session -> new VoucherValidationService(
            session.accounts(), session.partners(), session.moneyAccounts(), session.periods());

    private JdbcDemo() {
    }

    public static void seedCatalogs(UnitOfWork transactions) {
        transactions.execute(session -> {
            AccountingSnapshot existing = AccountingSnapshot.capture(session);
            if (!existing.accounts().isEmpty() || !existing.partners().isEmpty() || !existing.moneyAccounts().isEmpty()
                    || !existing.users().isEmpty() || !existing.periods().isEmpty() || !existing.documents().isEmpty()
                    || !existing.journal().isEmpty() || !existing.allocations().isEmpty() || !existing.cancellations().isEmpty()) {
                throw new IllegalStateException("Demo seed requires empty acc_ tables; existing data was kept");
            }
            session.accounts().insert(account("1111", AccountType.ASSET, AccountTracking.MONEY_ACCOUNT));
            session.accounts().insert(account("1121", AccountType.ASSET, AccountTracking.MONEY_ACCOUNT));
            session.accounts().insert(account("131", AccountType.ASSET, AccountTracking.PARTNER));
            session.accounts().insert(account("331", AccountType.LIABILITY, AccountTracking.PARTNER));
            session.accounts().insert(account("5113", AccountType.REVENUE, AccountTracking.NONE));
            session.accounts().insert(account("6427", AccountType.EXPENSE, AccountTracking.NONE));
            session.moneyAccounts().insert(new MoneyAccount("CASH01", "Demo cash", "1111", MoneyAccountKind.CASH));
            session.moneyAccounts().insert(new MoneyAccount("BANK01", "Demo bank", "1121", MoneyAccountKind.BANK));
            session.partners().insert(new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER)));
            session.partners().insert(new Partner("NCC01", "Supplier B", Set.of(PartnerRole.SUPPLIER)));
            session.periods().insert(AccountingPeriod.open(YearMonth.from(DATE)));
            session.users().insert(new User(1, "ketoan", "Accountant demo", Set.of(UserRole.ACCOUNTANT)));
            session.users().insert(new User(2, "truong", "Chief demo", Set.of(UserRole.CHIEF)));
            session.users().insert(new User(4, "viewer", "Reader demo", Set.of(UserRole.VIEWER)));
            return null;
        });
    }

    public static void run(PrintStream output, UnitOfWork transactions, Clock clock) {
        RecognitionDraft saleInput = new RecognitionDraft("DB_DEMO_DT001", DATE, DATE, "Persistent service revenue",
                "KH01", "131", List.of(new RecognitionItem("5113", Money.of(10_000_000))));
        SettlementDraft receiptInput = new SettlementDraft("DB_DEMO_PT001", DATE, DATE, "Persistent customer collection",
                "KH01", "131", "CASH01", Money.of(4_000_000));
        VoucherDocument sale = find(transactions, clock, "DB_DEMO_DT001");
        if (sale == null) {
            sale = new RecognitionService(transactions, VALIDATORS, clock).createRevenue(saleInput, 1);
        }
        requireSample(sale, VoucherSnapshot.from(new RevenueCreator().create(saleInput.toRevenueDraft())));
        JournalEntry saleEntry = post(transactions, clock, sale);
        VoucherDocument receipt = find(transactions, clock, "DB_DEMO_PT001");
        if (receipt == null) {
            receipt = new SettlementService(transactions, VALIDATORS, clock).createCustomerReceipt(receiptInput, 1);
        }
        requireSample(receipt, VoucherSnapshot.from(new ReceiptCreator().create(receiptInput.toReceiptDraft("1111"))));
        JournalEntry receiptEntry = post(transactions, clock, receipt);
        UUID allocationId = UUID.nameUUIDFromBytes("accounting-jdbc-demo-allocation-v1".getBytes(StandardCharsets.UTF_8));
        new DebtAllocationService(transactions, clock).allocate(new AllocationRequest(allocationId,
                key(saleEntry), key(receiptEntry), Money.of(4_000_000), DATE, "Persistent demo matching"), 2);

        LedgerService ledger = new LedgerService(transactions);
        DebtStatement debt = new DebtService(transactions).statement("131", "KH01", DATE, 4);
        output.println("PERSISTENT JDBC DEMO");
        output.println("Cash=" + ledger.netBalance("1111", DATE, 4) + "; customer A receivable=" + debt.netDebt());
        output.println("Open invoice amount=" + debt.unsettledDebt() + "; unallocated collections=" + debt.unallocatedSettlements());
        output.println("Journal entries=" + ledger.entries(4).size() + "; allocations=" + new DebtService(transactions).allocations(4).size());
        output.println("Sale journal=" + saleEntry.getId() + "; receipt journal=" + receiptEntry.getId());
        output.println("Data is stored in SQL. Run --db=check or repeat --demo=jdbc in another process to read it again.");
    }

    private static VoucherDocument find(UnitOfWork transactions, Clock clock, String reference) {
        return transactions.execute(session -> new VoucherWorkflowService(
                session.users(), session.documents(), VALIDATORS.create(session), clock).list(1).stream()
                .filter(value -> value.getContent().getReference().equals(reference)).findFirst().orElse(null));
    }

    private static void requireSample(VoucherDocument document, VoucherSnapshot expected) {
        if (document.getCreatedBy() != 1 || !VoucherSnapshot.from(document.getContent()).equals(expected)) {
            throw new IllegalStateException("Existing demo reference contains different data; no content was overwritten");
        }
    }

    private static JournalEntry post(UnitOfWork transactions, Clock clock, VoucherDocument source) {
        VoucherDocument approved = transactions.execute(session -> {
            VoucherWorkflowService workflow = new VoucherWorkflowService(
                    session.users(), session.documents(), VALIDATORS.create(session), clock);
            VoucherDocument current = workflow.get(source.getId(), 1);
            if (current.getStatus() == VoucherStatus.DRAFT || current.getStatus() == VoucherStatus.RETURNED) {
                current = workflow.submit(current.getId(), current.getVersion(), 1);
            }
            if (current.getStatus() == VoucherStatus.PENDING_APPROVAL) {
                current = workflow.approve(current.getId(), current.getVersion(), 2);
            }
            return current;
        });
        return new PostingService(transactions, VALIDATORS, clock).post(approved.getId(), approved.getVersion(), 2);
    }

    private static JournalLineKey key(JournalEntry entry) {
        JournalLine line = entry.getLines().stream().filter(value -> value.getAccountCode().equals("131")).findFirst().orElseThrow();
        return new JournalLineKey(entry.getId(), line.getLineNumber());
    }

    private static Account account(String code, AccountType type, AccountTracking tracking) {
        BalanceSide normal = type == AccountType.ASSET || type == AccountType.EXPENSE ? BalanceSide.DEBIT : BalanceSide.CREDIT;
        return Account.posting(code, "Account " + code, type, normal, null, tracking);
    }
}
