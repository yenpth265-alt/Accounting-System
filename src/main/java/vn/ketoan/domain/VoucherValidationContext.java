package vn.ketoan.domain;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Immutable master-data snapshots for one validation, with no storage dependency. */
public final class VoucherValidationContext {

    private final Map<String, Account> accounts;
    private final Map<String, Partner> partners;
    private final Map<String, MoneyAccount> moneyAccounts;
    private final AccountingPeriod period;

    public VoucherValidationContext(Collection<Account> accounts, Collection<Partner> partners,
                                    Collection<MoneyAccount> moneyAccounts, AccountingPeriod period) {
        this.accounts = Objects.requireNonNull(accounts, "Accounts are required").stream()
                .collect(Collectors.toUnmodifiableMap(Account::getCode, Function.identity()));
        this.partners = Objects.requireNonNull(partners, "Partners are required").stream()
                .collect(Collectors.toUnmodifiableMap(Partner::getCode, Function.identity()));
        this.moneyAccounts = Objects.requireNonNull(moneyAccounts, "Money accounts are required").stream()
                .collect(Collectors.toUnmodifiableMap(MoneyAccount::getCode, Function.identity()));
        this.period = Objects.requireNonNull(period, "Accounting period is required");
    }

    public Account requireAccount(String code) {
        Account account = accounts.get(Account.normalizeCode(code));
        if (account == null) {
            throw new IllegalArgumentException("Account not found: " + code);
        }
        return account;
    }

    public Partner requirePartner(String code) {
        Partner partner = partners.get(Partner.normalizeCode(code));
        if (partner == null) {
            throw new IllegalArgumentException("Partner not found: " + code);
        }
        return partner;
    }

    public MoneyAccount requireMoneyAccount(String code) {
        MoneyAccount account = moneyAccounts.get(MoneyAccount.normalizeCode(code));
        if (account == null) {
            throw new IllegalArgumentException("Money account not found: " + code);
        }
        return account;
    }

    public AccountingPeriod getPeriod() {
        return period;
    }
}
