package vn.ketoan.application.service;

import java.util.List;
import java.util.Objects;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.MoneyAccount;

public final class MoneyAccountService {

    private final MoneyAccountRepository moneyAccounts;
    private final AccountRepository accounts;

    public MoneyAccountService(MoneyAccountRepository moneyAccounts, AccountRepository accounts) {
        this.moneyAccounts = Objects.requireNonNull(moneyAccounts, "Money account repository is required");
        this.accounts = Objects.requireNonNull(accounts, "Account repository is required");
    }

    public void add(MoneyAccount account) {
        Objects.requireNonNull(account, "Money account is required");
        validateBinding(account);
        moneyAccounts.insert(account);
    }

    private void validateBinding(MoneyAccount account) {
        Account ledgerAccount = accounts.findByCode(account.getLedgerAccountCode()).orElseThrow(
                () -> new IllegalArgumentException("Ledger account not found: " + account.getLedgerAccountCode()));
        account.requireUsableWith(ledgerAccount);
    }

    public MoneyAccount get(String code) {
        String normalized = MoneyAccount.normalizeCode(code);
        return moneyAccounts.findByCode(normalized).orElseThrow(
                () -> new IllegalArgumentException("Money account not found: " + normalized));
    }

    public List<MoneyAccount> list() {
        return moneyAccounts.findAll();
    }

    public void deactivate(String code) {
        moneyAccounts.update(get(code).deactivate());
    }

    public void activate(String code) {
        MoneyAccount account = get(code).activate();
        validateBinding(account);
        moneyAccounts.update(account);
    }
}
