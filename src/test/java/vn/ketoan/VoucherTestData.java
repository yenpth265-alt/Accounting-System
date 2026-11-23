package vn.ketoan;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import vn.ketoan.application.service.VoucherValidationService;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.MoneyAccountKind;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.VoucherValidationContext;
import vn.ketoan.domain.value.Money;
import vn.ketoan.infrastructure.memory.InMemoryAccountRepository;
import vn.ketoan.infrastructure.memory.InMemoryAccountingPeriodRepository;
import vn.ketoan.infrastructure.memory.InMemoryMoneyAccountRepository;
import vn.ketoan.infrastructure.memory.InMemoryPartnerRepository;

public final class VoucherTestData {

    public static final LocalDate DATE = LocalDate.of(2026, 11, 24);

    private VoucherTestData() {
    }

    public static List<Account> accounts() {
        return List.of(
                Account.group("111", "Cash", AccountType.ASSET, BalanceSide.DEBIT, null),
                account("1111", AccountType.ASSET, AccountTracking.MONEY_ACCOUNT),
                account("1121", AccountType.ASSET, AccountTracking.MONEY_ACCOUNT),
                account("131", AccountType.ASSET, AccountTracking.PARTNER),
                account("331", AccountType.LIABILITY, AccountTracking.PARTNER),
                account("5113", AccountType.REVENUE, AccountTracking.NONE),
                account("6427", AccountType.EXPENSE, AccountTracking.NONE),
                account("4111", AccountType.EQUITY, AccountTracking.NONE),
                account("33311", AccountType.LIABILITY, AccountTracking.NONE),
                account("1331", AccountType.ASSET, AccountTracking.NONE));
    }

    public static Account account(String code, AccountType type, AccountTracking tracking) {
        BalanceSide normal = type == AccountType.ASSET || type == AccountType.EXPENSE
                ? BalanceSide.DEBIT : BalanceSide.CREDIT;
        return Account.posting(code, "Account " + code, type, normal, null, tracking);
    }

    public static List<Partner> partners() {
        return List.of(new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER)),
                new Partner("KH02", "Customer B", Set.of(PartnerRole.CUSTOMER)),
                new Partner("NCC01", "Supplier B", Set.of(PartnerRole.SUPPLIER)),
                new Partner("DT01", "Both roles", Set.of(PartnerRole.CUSTOMER, PartnerRole.SUPPLIER)));
    }

    public static List<MoneyAccount> moneyAccounts() {
        return List.of(new MoneyAccount("CASH01", "Main cash", "1111", MoneyAccountKind.CASH),
                new MoneyAccount("CASH02", "Second cash", "1111", MoneyAccountKind.CASH),
                new MoneyAccount("BANK01", "Bank", "1121", MoneyAccountKind.BANK));
    }

    public static VoucherValidationContext context() {
        return new VoucherValidationContext(accounts(), partners(), moneyAccounts(),
                AccountingPeriod.open(YearMonth.from(DATE)));
    }

    public static Draft draft(VoucherLine... lines) {
        return new Draft("TEST001", DATE, DATE, "Test business event", List.of(lines));
    }

    public static VoucherLine debit(String account, long amount, String partner, String fund) {
        return VoucherLine.debit(account, Money.of(amount), partner, fund);
    }

    public static VoucherLine credit(String account, long amount, String partner, String fund) {
        return VoucherLine.credit(account, Money.of(amount), partner, fund);
    }

    public static Draft revenue() {
        return draft(debit("131", 10_000_000, "KH01", null), credit("5113", 10_000_000, null, null));
    }

    public static Draft expense() {
        return draft(debit("6427", 3_000_000, null, null), credit("331", 3_000_000, "NCC01", null));
    }

    public static Draft receipt() {
        return draft(debit("1111", 4_000_000, null, "CASH01"), credit("131", 4_000_000, "KH01", null));
    }

    public static Draft payment() {
        return draft(debit("331", 1_000_000, "NCC01", null), credit("1111", 1_000_000, null, "CASH01"));
    }

    public static Draft general() {
        return draft(debit("1111", 2_000_000, null, "CASH01"), credit("1121", 2_000_000, null, "BANK01"));
    }

    public static Stores stores() {
        Stores stores = new Stores(new InMemoryAccountRepository(), new InMemoryPartnerRepository(),
                new InMemoryMoneyAccountRepository(), new InMemoryAccountingPeriodRepository());
        accounts().forEach(stores.accounts()::insert);
        partners().forEach(stores.partners()::insert);
        moneyAccounts().forEach(stores.moneyAccounts()::insert);
        stores.periods().insert(AccountingPeriod.open(YearMonth.from(DATE)));
        return stores;
    }

    public record Stores(InMemoryAccountRepository accounts, InMemoryPartnerRepository partners,
                         InMemoryMoneyAccountRepository moneyAccounts,
                         InMemoryAccountingPeriodRepository periods) {

        public VoucherValidationService validator() {
            return new VoucherValidationService(accounts, partners, moneyAccounts, periods);
        }
    }
}
