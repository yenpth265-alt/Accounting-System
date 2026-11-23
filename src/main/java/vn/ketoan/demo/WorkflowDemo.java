package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.application.port.UserRepository;
import vn.ketoan.application.port.VoucherDocumentRepository;
import vn.ketoan.application.service.AccountService;
import vn.ketoan.application.service.AccountingPeriodService;
import vn.ketoan.application.service.MoneyAccountService;
import vn.ketoan.application.service.PartnerService;
import vn.ketoan.application.service.VoucherValidationService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.MoneyAccountKind;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryAccountRepository;
import vn.ketoan.infrastructure.memory.InMemoryAccountingPeriodRepository;
import vn.ketoan.infrastructure.memory.InMemoryMoneyAccountRepository;
import vn.ketoan.infrastructure.memory.InMemoryPartnerRepository;
import vn.ketoan.infrastructure.memory.InMemoryUserRepository;
import vn.ketoan.infrastructure.memory.InMemoryVoucherDocumentRepository;

public final class WorkflowDemo {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 24);

    private WorkflowDemo() {
    }

    public static void run(PrintStream output) {
        AccountRepository accountRepository = new InMemoryAccountRepository();
        PartnerRepository partnerRepository = new InMemoryPartnerRepository();
        MoneyAccountRepository moneyRepository = new InMemoryMoneyAccountRepository();
        AccountingPeriodRepository periodRepository = new InMemoryAccountingPeriodRepository();
        UserRepository users = new InMemoryUserRepository();
        VoucherDocumentRepository documents = new InMemoryVoucherDocumentRepository();
        AccountService accounts = new AccountService(accountRepository);
        accounts.add(Account.group("111", "Cash", AccountType.ASSET, BalanceSide.DEBIT, null));
        accounts.add(Account.posting("1111", "Cash in VND", AccountType.ASSET,
                BalanceSide.DEBIT, "111", AccountTracking.MONEY_ACCOUNT));
        accounts.add(Account.posting("131", "Receivables", AccountType.ASSET,
                BalanceSide.DEBIT, null, AccountTracking.PARTNER));
        new PartnerService(partnerRepository).add(new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER)));
        new MoneyAccountService(moneyRepository, accountRepository)
                .add(new MoneyAccount("CASH01", "Cash fund", "1111", MoneyAccountKind.CASH));
        new AccountingPeriodService(periodRepository).openPeriod(YearMonth.from(DATE));
        users.insert(new User(1, "ketoan", "Accountant", Set.of(UserRole.ACCOUNTANT)));
        users.insert(new User(2, "truong", "Chief accountant", Set.of(UserRole.CHIEF)));
        users.insert(new User(3, "ketoan2", "Other accountant", Set.of(UserRole.ACCOUNTANT)));
        users.insert(new User(4, "viewer", "Viewer", Set.of(UserRole.VIEWER)));
        users.insert(new User(5, "admin", "Administrator", Set.of(UserRole.ADMIN)));
        users.insert(new User(6, "combined", "Combined roles", Set.of(UserRole.ACCOUNTANT, UserRole.CHIEF)));
        VoucherValidationService validator = new VoucherValidationService(accountRepository,
                partnerRepository, moneyRepository, periodRepository);
        Clock clock = Clock.fixed(Instant.parse("2026-11-24T03:00:00Z"), ZoneOffset.UTC);
        VoucherWorkflowService service = new VoucherWorkflowService(users, documents, validator, clock);

        output.println("VOUCHER WORKFLOW DEMO");
        output.println("Storage: in-memory. Demo uses trusted user ids, not password authentication.");
        users.findAll().forEach(user -> output.printf("USER: %d | %s | %s%n", user.getId(),
                user.getUsername(), user.getRoles().stream().sorted().toList()));

        VoucherDocument created = service.create(new ReceiptCreator(), receipt("WF001", 4_000_000)
                .withLines(List.of()), 1);
        show(output, "Created incomplete draft", created);
        showRejected(output, "Viewer edits content", () -> service.edit(created.getId(), 0,
                new ReceiptCreator(), receipt("WF001", 4_000_000), 4));
        showRejected(output, "Other accountant edits owner's content", () -> service.edit(created.getId(), 0,
                new ReceiptCreator(), receipt("WF001", 4_000_000), 3));
        showRejected(output, "Submit incomplete draft", () -> service.submit(created.getId(), 0, 1));

        VoucherDocument edited = service.edit(created.getId(), 0, new ReceiptCreator(), receipt("WF001", 4_000_000), 1);
        show(output, "Owner completed content", edited);
        VoucherDocument pending = service.submit(edited.getId(), edited.getVersion(), 1);
        show(output, "Owner submitted", pending);
        showRejected(output, "Edit while pending", () -> service.edit(pending.getId(), pending.getVersion(),
                new ReceiptCreator(), receipt("WF001", 5_000_000), 1));

        VoucherDocument returned = service.returnForRevision(pending.getId(), pending.getVersion(), 2, "Correct amount");
        show(output, "Chief returned with reason", returned);
        VoucherDocument revised = service.edit(returned.getId(), returned.getVersion(), new ReceiptCreator(),
                receipt("WF001", 5_000_000), 1);
        VoucherDocument resubmitted = service.submit(revised.getId(), revised.getVersion(), 1);
        VoucherDocument approved = service.approve(resubmitted.getId(), resubmitted.getVersion(), 2);
        show(output, "Chief approved revised content", approved);
        showRejected(output, "Edit approved content", () -> service.edit(approved.getId(), approved.getVersion(),
                new ReceiptCreator(), receipt("WF001", 4_000_000), 1));

        VoucherDocument withdrawn = service.withdrawApproval(approved.getId(), approved.getVersion(), 2,
                "Recheck source document");
        show(output, "Chief withdrew approval", withdrawn);
        VoucherDocument corrected = service.edit(withdrawn.getId(), withdrawn.getVersion(), new ReceiptCreator(),
                receipt("WF001", 4_000_000), 1);
        VoucherDocument submittedAgain = service.submit(corrected.getId(), corrected.getVersion(), 1);
        VoucherDocument approvedAgain = service.approve(submittedAgain.getId(), submittedAgain.getVersion(), 2);
        show(output, "Approved after correction", approvedAgain);
        showRejected(output, "Save using old version", () -> service.submit(approvedAgain.getId(), 0, 1));

        VoucherDocument combined = service.create(new ReceiptCreator(), receipt("WF002", 1_000_000), 6);
        VoucherDocument combinedPending = service.submit(combined.getId(), combined.getVersion(), 6);
        showRejected(output, "Combined roles approve own document",
                () -> service.approve(combinedPending.getId(), combinedPending.getVersion(), 6));
        VoucherDocument cancelledDraft = service.create(new ReceiptCreator(), receipt("WF003", 1_000_000), 1);
        VoucherDocument cancelled = service.cancel(cancelledDraft.getId(), 0, 1, "Duplicate business event");
        show(output, "Owner cancelled draft", cancelled);
        showRejected(output, "Resubmit cancelled document",
                () -> service.submit(cancelled.getId(), cancelled.getVersion(), 1));
        showRejected(output, "Admin reads accounting without accounting role", () -> service.list(5));

        output.println();
        output.println("AUDIT WF001: version | action | actor | from -> to | time(+07) | first line amount | note");
        service.history(approvedAgain.getId(), 4).forEach(entry -> {
            Money amount = entry.content().lines().isEmpty() ? Money.ZERO : entry.content().lines().getFirst().amount();
            output.printf("%d | %s | %d | %s -> %s | %s | %s | %s%n", entry.version(), entry.action(),
                    entry.actorId(), entry.fromStatus() == null ? "-" : entry.fromStatus(), entry.toStatus(),
                    entry.occurredAt().atZone(ZoneId.of("Asia/Ho_Chi_Minh")).toOffsetDateTime(), amount, entry.note());
        });
        output.println("Stored documents visible to viewer: " + service.list(4).size());
        output.println("APPROVED is not POSTED: no ledger entries or balances were changed.");
    }

    private static Draft receipt(String reference, long amount) {
        return new Draft(reference, DATE, DATE, "Customer repayment", List.of(
                VoucherLine.debit("1111", Money.of(amount), null, "CASH01"),
                VoucherLine.credit("131", Money.of(amount), "KH01", null)));
    }

    private static void show(PrintStream output, String operation, VoucherDocument document) {
        output.printf("%s -> %s | version=%d | history=%d%n", operation, document.getStatus(),
                document.getVersion(), document.getHistory().size());
    }

    private static void showRejected(PrintStream output, String operation, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException | AccessDeniedException exception) {
            output.printf("REJECTED: %s -> %s%n", operation, exception.getMessage());
            return;
        }
        throw new IllegalStateException("Demo did not reject: " + operation);
    }
}
