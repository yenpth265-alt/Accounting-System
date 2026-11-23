package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.application.service.AccountService;
import vn.ketoan.application.service.AccountingPeriodService;
import vn.ketoan.application.service.MoneyAccountService;
import vn.ketoan.application.service.PartnerService;
import vn.ketoan.application.service.VoucherCreationService;
import vn.ketoan.application.service.VoucherValidationService;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ExpenseCreator;
import vn.ketoan.domain.GeneralCreator;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.MoneyAccountKind;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.PaymentCreator;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.RevenueCreator;
import vn.ketoan.domain.Voucher;
import vn.ketoan.domain.VoucherCreator;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryAccountRepository;
import vn.ketoan.infrastructure.memory.InMemoryAccountingPeriodRepository;
import vn.ketoan.infrastructure.memory.InMemoryMoneyAccountRepository;
import vn.ketoan.infrastructure.memory.InMemoryPartnerRepository;

/** Executable composition root demonstrating Factory Method, Template Method and delegation. */
public final class VoucherDemo {

    private static final LocalDate DATE = LocalDate.of(2026, 11, 24);

    private VoucherDemo() {
    }

    public static void run(PrintStream output) {
        AccountRepository accountRepository = new InMemoryAccountRepository();
        PartnerRepository partnerRepository = new InMemoryPartnerRepository();
        MoneyAccountRepository moneyRepository = new InMemoryMoneyAccountRepository();
        AccountingPeriodRepository periodRepository = new InMemoryAccountingPeriodRepository();
        AccountService accounts = new AccountService(accountRepository);
        PartnerService partners = new PartnerService(partnerRepository);
        MoneyAccountService moneyAccounts = new MoneyAccountService(moneyRepository, accountRepository);
        AccountingPeriodService periods = new AccountingPeriodService(periodRepository);
        seedAccounts(accounts);
        partners.add(new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER)));
        partners.add(new Partner("NCC01", "Supplier B", Set.of(PartnerRole.SUPPLIER)));
        moneyAccounts.add(new MoneyAccount("CASH01", "Main cash", "1111", MoneyAccountKind.CASH));
        moneyAccounts.add(new MoneyAccount("BANK01", "Demand deposit", "1121", MoneyAccountKind.BANK));
        periods.openPeriod(YearMonth.from(DATE));
        // Historical fixture only, not an execution of financial closing.
        periodRepository.insert(AccountingPeriod.open(YearMonth.of(2026, 10))
                .beginClosing().completeClosing());
        VoucherValidator validator = new VoucherValidationService(accountRepository,
                partnerRepository, moneyRepository, periodRepository);

        Draft revenue = draft("DT001", debit("131", 10_000_000, "KH01", null),
                credit("5113", 10_000_000, null, null));
        Draft expense = draft("CP001", debit("6427", 3_000_000, null, null),
                credit("331", 3_000_000, "NCC01", null));
        Draft receipt = draft("PT001", debit("1111", 4_000_000, null, "CASH01"),
                credit("131", 4_000_000, "KH01", null));
        Draft payment = draft("PC001", debit("331", 1_000_000, "NCC01", null),
                credit("1111", 1_000_000, null, "CASH01"));
        Draft transfer = draft("PK001", debit("1111", 2_000_000, null, "CASH01"),
                credit("1121", 2_000_000, null, "BANK01"));

        output.println("VOUCHER PATTERNS DEMO");
        output.println("Storage: current master data in memory; no database connection.");
        moneyAccounts.list().forEach(account -> output.printf("FUND: %s -> ledger %s (%s)%n",
                account.getCode(), account.getLedgerAccountCode(), account.getKind()));

        List<Example> examples = List.of(new Example(new RevenueCreator(), revenue),
                new Example(new ExpenseCreator(), expense), new Example(new ReceiptCreator(), receipt),
                new Example(new PaymentCreator(), payment), new Example(new GeneralCreator(), transfer));
        for (Example example : examples) {
            VoucherCreationService service = new VoucherCreationService(example.creator(), validator);
            Voucher voucher = service.createValidated(example.draft());
            output.println();
            output.printf("FACTORY: %s -> %s%n", example.creator().getClass().getSimpleName(),
                    voucher.getClass().getSimpleName());
            output.printf("READY: %s | %s | debit=%s | credit=%s%n", voucher.getReference(),
                    voucher.getType(), voucher.getTotalDebit(), voucher.getTotalCredit());
            voucher.getLines().forEach(line -> output.printf("  %s | debit=%s | credit=%s | partner=%s | fund=%s%n",
                    line.getAccountCode(), line.getDebit(), line.getCredit(),
                    line.getPartnerCode().orElse("-"), line.getMoneyAccountCode().orElse("-")));
        }

        output.println();
        showRejected(output, "Unbalanced revenue", () -> new VoucherCreationService(new RevenueCreator(), validator)
                .createValidated(draft("BAD01", debit("131", 10_000_000, "KH01", null),
                        credit("5113", 9_000_000, null, null))));
        showRejected(output, "Missing customer", () -> validator.validate(new ReceiptCreator().create(
                draft("BAD02", debit("1111", 10, null, "CASH01"), credit("131", 10, null, null)))));
        showRejected(output, "Fund linked to another ledger", () -> validator.validate(new ReceiptCreator().create(
                draft("BAD03", debit("1111", 10, null, "BANK01"), credit("131", 10, "KH01", null)))));
        showRejected(output, "Revenue recorded again on receipt", () -> validator.validate(new ReceiptCreator().create(
                draft("BAD04", debit("1111", 10, null, "CASH01"), credit("5113", 10, null, null)))));
        showRejected(output, "Customer used as supplier", () -> validator.validate(new ExpenseCreator().create(
                draft("BAD05", debit("6427", 10, null, null), credit("331", 10, "KH01", null)))));

        Draft oldDate = new Draft("BAD06", DATE, LocalDate.of(2026, 10, 31),
                "Closed period example", receipt.getLines());
        showRejected(output, "Closed period", () -> validator.validate(new ReceiptCreator().create(oldDate)));
        Voucher existing = new ReceiptCreator().create(receipt);
        moneyAccounts.deactivate("CASH01");
        showRejected(output, "Current fund was deactivated", () -> validator.validate(existing));
        moneyAccounts.activate("CASH01");

        Voucher incomplete = new ReceiptCreator().create(draft("DRAFT01"));
        output.println("DRAFT CREATED: zero lines are allowed before submission.");
        showRejected(output, "Submitting incomplete draft", () -> validator.validate(incomplete));
        output.println("Checks only: no approval, voucher persistence or ledger posting has occurred.");
    }

    private static void seedAccounts(AccountService accounts) {
        accounts.add(Account.group("111", "Cash", AccountType.ASSET, BalanceSide.DEBIT, null));
        accounts.add(Account.posting("1111", "Cash in VND", AccountType.ASSET,
                BalanceSide.DEBIT, "111", AccountTracking.MONEY_ACCOUNT));
        accounts.add(Account.posting("1121", "Demand deposit", AccountType.ASSET,
                BalanceSide.DEBIT, null, AccountTracking.MONEY_ACCOUNT));
        accounts.add(Account.posting("131", "Receivables", AccountType.ASSET,
                BalanceSide.DEBIT, null, AccountTracking.PARTNER));
        accounts.add(Account.posting("331", "Payables", AccountType.LIABILITY,
                BalanceSide.CREDIT, null, AccountTracking.PARTNER));
        accounts.add(Account.posting("5113", "Service revenue", AccountType.REVENUE,
                BalanceSide.CREDIT, null, AccountTracking.NONE));
        accounts.add(Account.posting("6427", "Administrative services", AccountType.EXPENSE,
                BalanceSide.DEBIT, null, AccountTracking.NONE));
    }

    private static Draft draft(String reference, VoucherLine... lines) {
        return new Draft(reference, DATE, DATE, "Demo business event", List.of(lines));
    }

    private static VoucherLine debit(String account, long amount, String partner, String fund) {
        return VoucherLine.debit(account, Money.of(amount), partner, fund);
    }

    private static VoucherLine credit(String account, long amount, String partner, String fund) {
        return VoucherLine.credit(account, Money.of(amount), partner, fund);
    }

    private static void showRejected(PrintStream output, String operation, Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException | IllegalStateException exception) {
            output.printf("REJECTED: %s -> %s%n", operation, exception.getMessage());
            return;
        }
        throw new IllegalStateException("Demo validation did not reject: " + operation);
    }

    private record Example(VoucherCreator creator, Draft draft) {
    }
}
