package vn.ketoan.demo;

import java.io.PrintStream;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.application.service.AccountService;
import vn.ketoan.application.service.AccountingPeriodService;
import vn.ketoan.application.service.PartnerService;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.infrastructure.memory.InMemoryAccountRepository;
import vn.ketoan.infrastructure.memory.InMemoryAccountingPeriodRepository;
import vn.ketoan.infrastructure.memory.InMemoryPartnerRepository;

/** A repeatable demonstration with a fresh, isolated in-memory data set. */
public final class MasterDataDemo {

    private MasterDataDemo() {
    }

    public static void run(PrintStream output) {
        AccountRepository accountRepository = new InMemoryAccountRepository();
        PartnerRepository partnerRepository = new InMemoryPartnerRepository();
        AccountingPeriodRepository periodRepository = new InMemoryAccountingPeriodRepository();

        AccountService accounts = new AccountService(accountRepository);
        PartnerService partners = new PartnerService(partnerRepository);
        AccountingPeriodService periods = new AccountingPeriodService(periodRepository);

        seedAccounts(accounts);
        partners.add(new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER)));
        partners.add(new Partner("NCC01", "Supplier B", Set.of(PartnerRole.SUPPLIER)));
        partners.add(new Partner("DT01", "Customer and supplier",
                Set.of(PartnerRole.CUSTOMER, PartnerRole.SUPPLIER)));

        // Historical demo state, not the execution of financial closing entries.
        periodRepository.insert(AccountingPeriod.open(YearMonth.of(2026, 10))
                .beginClosing().completeClosing());
        periods.openPeriod(YearMonth.of(2026, 11));

        output.println("MASTER DATA DEMO");
        output.println("Storage: in-memory; data is reset on every run.");
        output.println();
        output.println("ACCOUNTS: code | name | type | normal balance | parent | posting | tracking");
        accounts.list().forEach(account -> output.printf("%s | %s | %s | %s | %s | %s | %s%n",
                account.getCode(), account.getName(), account.getType(), account.getNormalBalance(),
                account.getParentCode().orElse("-"), account.isPostingAllowed(), account.getTracking()));

        output.println();
        output.println("PARTNERS: code | name | roles");
        partners.list().forEach(partner -> output.printf("%s | %s | %s%n",
                partner.getCode(), partner.getName(), partner.getRoles().stream().sorted().toList()));

        output.println();
        output.println("PERIODS: month | start | end | status");
        periods.list().forEach(period -> output.printf("%s | %s | %s | %s%n",
                period.getMonth(), period.getStartDate(), period.getEndDate(), period.getStatus()));

        accounts.requirePostingAccount("1111");
        partners.requireRole("KH01", PartnerRole.CUSTOMER);
        periods.requireOpenPeriod(LocalDate.of(2026, 11, 23));
        output.println();
        output.println("VALID: account 1111, customer KH01, posting date 2026-11-23.");

        showRejected(output, "Posting to group account 111",
                () -> accounts.requirePostingAccount("111"));
        showRejected(output, "Using KH01 as a supplier",
                () -> partners.requireRole("KH01", PartnerRole.SUPPLIER));
        showRejected(output, "Posting to closed period 2026-10",
                () -> periods.requireOpenPeriod(LocalDate.of(2026, 10, 31)));
        showRejected(output, "Adding a duplicate account",
                () -> accounts.add(Account.posting("1111", "Duplicate cash account", AccountType.ASSET,
                        BalanceSide.DEBIT, "111", AccountTracking.MONEY_ACCOUNT)));
        showRejected(output, "Deactivating a parent with active children",
                () -> accounts.deactivate("111"));

        accounts.deactivate("1111");
        showRejected(output, "Using an inactive account",
                () -> accounts.requirePostingAccount("1111"));
        accounts.activate("1111");
        output.println("RESTORED: account 1111 is active again.");
    }

    private static void seedAccounts(AccountService accounts) {
        accounts.add(Account.group("111", "Cash", AccountType.ASSET, BalanceSide.DEBIT, null));
        accounts.add(Account.posting("1111", "Cash in VND", AccountType.ASSET,
                BalanceSide.DEBIT, "111", AccountTracking.MONEY_ACCOUNT));
        accounts.add(Account.group("112", "Demand deposits", AccountType.ASSET, BalanceSide.DEBIT, null));
        accounts.add(Account.posting("1121", "Demand deposits in VND", AccountType.ASSET,
                BalanceSide.DEBIT, "112", AccountTracking.MONEY_ACCOUNT));
        accounts.add(Account.posting("131", "Customer receivables", AccountType.ASSET,
                BalanceSide.DEBIT, null, AccountTracking.PARTNER));
        accounts.add(Account.posting("331", "Supplier payables", AccountType.LIABILITY,
                BalanceSide.CREDIT, null, AccountTracking.PARTNER));
        accounts.add(Account.group("511", "Revenue", AccountType.REVENUE, BalanceSide.CREDIT, null));
        accounts.add(Account.posting("5113", "Service revenue", AccountType.REVENUE,
                BalanceSide.CREDIT, "511", AccountTracking.NONE));
        accounts.add(Account.group("642", "Administrative expenses", AccountType.EXPENSE,
                BalanceSide.DEBIT, null));
        accounts.add(Account.posting("6427", "Purchased administrative services", AccountType.EXPENSE,
                BalanceSide.DEBIT, "642", AccountTracking.NONE));
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
}
