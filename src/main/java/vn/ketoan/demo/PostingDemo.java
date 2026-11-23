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

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.application.service.AccountService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.application.service.MoneyAccountService;
import vn.ketoan.application.service.PostingService;
import vn.ketoan.application.service.VoucherValidationService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.MoneyAccountKind;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherCreator;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryUnitOfWork;

public final class PostingDemo {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 24);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ZoneOffset.UTC);
    private static final VoucherValidatorFactory VALIDATORS = session -> new VoucherValidationService(
            session.accounts(), session.partners(), session.moneyAccounts(), session.periods());

    private PostingDemo() {
    }

    public static void run(PrintStream output) {
        UnitOfWork transactions = new InMemoryUnitOfWork();
        seed(transactions);
        PostingService posting = new PostingService(transactions, VALIDATORS, CLOCK);
        LedgerService ledger = new LedgerService(transactions);
        VoucherDocument sale = approved(transactions, new RevenueCreator(), new Draft("DT001", DATE, DATE,
                "Completed service on credit", List.of(
                        VoucherLine.debit("131", Money.of(10_000_000), "KH01", null),
                        VoucherLine.credit("5113", Money.of(10_000_000), null, null))));
        VoucherDocument receipt = approved(transactions, new ReceiptCreator(), receipt("PT001", 4_000_000));

        output.println("ATOMIC POSTING DEMO");
        output.println("One in-memory UnitOfWork holds catalogs, users, documents, audit and journal.");
        output.println("Approved documents before posting: journal entries=" + ledger.entries(4).size());
        printBalances(output, ledger);
        JournalEntry revenueEntry = posting.post(sale.getId(), sale.getVersion(), 2);
        output.println("POSTED DT001: receivable +10000000; revenue credit +10000000.");
        printBalances(output, ledger);
        JournalEntry receiptEntry = posting.post(receipt.getId(), receipt.getVersion(), 2);
        output.println("POSTED PT001: cash +4000000; receivable -4000000.");
        printBalances(output, ledger);

        JournalEntry repeated = posting.post(receipt.getId(), receipt.getVersion(), 2);
        output.println("RETRY PT001: same journal id=" + repeated.getId().equals(receiptEntry.getId())
                + "; entries=" + ledger.entries(4).size());

        VoucherDocument interrupted = approved(transactions, new ReceiptCreator(), receipt("PT002", 1_000_000));
        try {
            transactions.execute(session -> {
                JournalEntry temporary = JournalEntry.fromApproved(UUID.randomUUID(), interrupted,
                        session.users().findById(2).orElseThrow(), CLOCK.instant());
                session.journal().append(temporary);
                output.println("STAGED PT002: temporary entries=" + session.journal().findAll().size());
                throw new IllegalStateException("Simulated interruption after journal append");
            });
        } catch (IllegalStateException expected) {
            output.println("ROLLBACK: " + expected.getMessage());
        }
        VoucherDocument unchanged = transactions.execute(session ->
                session.documents().findById(interrupted.getId()).orElseThrow());
        output.println("AFTER ROLLBACK: entries=" + ledger.entries(4).size()
                + "; PT002=" + unchanged.getStatus() + "; history=" + unchanged.getHistory().size());
        printBalances(output, ledger);

        output.println("JOURNAL: reference | row | account | debit | credit | partner | fund");
        ledger.entries(4).forEach(entry -> entry.getLines().forEach(line -> output.printf(
                "%s | %d | %s | %s | %s | %s | %s%n", entry.getContent().reference(),
                line.getLineNumber(), line.getAccountCode(), line.getDebit(), line.getCredit(),
                line.getPartnerCode().orElse("-"), line.getMoneyAccountCode().orElse("-"))));
        output.println("Source links verified: " + revenueEntry.getSourceDocumentId().equals(sale.getId())
                + ", " + receiptEntry.getSourceDocumentId().equals(receipt.getId()));
        output.println("Storage is temporary memory; restart resets the demo. MySQL is not connected yet.");
    }

    private static void seed(UnitOfWork transactions) {
        transactions.execute(session -> {
            AccountService accounts = new AccountService(session.accounts());
            accounts.add(Account.group("111", "Cash", AccountType.ASSET, BalanceSide.DEBIT, null));
            accounts.add(Account.posting("1111", "Cash in VND", AccountType.ASSET,
                    BalanceSide.DEBIT, "111", AccountTracking.MONEY_ACCOUNT));
            accounts.add(Account.posting("131", "Receivables", AccountType.ASSET,
                    BalanceSide.DEBIT, null, AccountTracking.PARTNER));
            accounts.add(Account.posting("5113", "Revenue", AccountType.REVENUE,
                    BalanceSide.CREDIT, null, AccountTracking.NONE));
            session.partners().insert(new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER)));
            new MoneyAccountService(session.moneyAccounts(), session.accounts())
                    .add(new MoneyAccount("CASH01", "Cash fund", "1111", MoneyAccountKind.CASH));
            session.periods().insert(vn.ketoan.domain.AccountingPeriod.open(YearMonth.from(DATE)));
            session.users().insert(new User(1, "ketoan", "Accountant", Set.of(UserRole.ACCOUNTANT)));
            session.users().insert(new User(2, "truong", "Chief accountant", Set.of(UserRole.CHIEF)));
            session.users().insert(new User(4, "viewer", "Viewer", Set.of(UserRole.VIEWER)));
            return null;
        });
    }

    private static VoucherDocument approved(UnitOfWork transactions, VoucherCreator creator, Draft input) {
        return transactions.execute(session -> {
            VoucherWorkflowService workflow = workflow(session);
            VoucherDocument draft = workflow.create(creator, input, 1);
            VoucherDocument pending = workflow.submit(draft.getId(), 0, 1);
            return workflow.approve(pending.getId(), pending.getVersion(), 2);
        });
    }

    private static VoucherWorkflowService workflow(AccountingSession session) {
        return new VoucherWorkflowService(session.users(), session.documents(), VALIDATORS.create(session), CLOCK);
    }

    private static Draft receipt(String reference, long amount) {
        return new Draft(reference, DATE, DATE, "Customer repayment", List.of(
                VoucherLine.debit("1111", Money.of(amount), null, "CASH01"),
                VoucherLine.credit("131", Money.of(amount), "KH01", null)));
    }

    private static void printBalances(PrintStream output, LedgerService ledger) {
        output.printf("BALANCES: cash=%s; customer A receivable=%s%n", ledger.netBalance("1111", DATE, 4),
                ledger.partnerNetBalance("131", "KH01", DATE, 4));
    }
}
