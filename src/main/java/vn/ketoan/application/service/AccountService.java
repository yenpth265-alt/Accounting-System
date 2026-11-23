package vn.ketoan.application.service;

import java.util.List;
import java.util.Objects;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.domain.Account;

public final class AccountService {

    private final AccountRepository accounts;

    public AccountService(AccountRepository accounts) {
        this.accounts = Objects.requireNonNull(accounts, "Account repository is required");
    }

    public void add(Account account) {
        Objects.requireNonNull(account, "Account is required");
        validateParent(account);
        accounts.insert(account);
    }

    private void validateParent(Account account) {
        account.getParentCode().ifPresent(parentCode -> {
            Account parent = get(parentCode);
            if (!parent.isActive()) {
                throw new IllegalStateException("Parent account is inactive: " + parentCode);
            }
            if (parent.isPostingAllowed()) {
                throw new IllegalStateException("A posting account cannot have children: " + parentCode);
            }
            if (parent.getType() != account.getType()) {
                throw new IllegalArgumentException("Parent and child account types must match");
            }
        });
    }

    public Account get(String code) {
        String normalized = Account.normalizeCode(code);
        return accounts.findByCode(normalized).orElseThrow(
                () -> new IllegalArgumentException("Account not found: " + normalized));
    }

    public List<Account> list() {
        return accounts.findAll();
    }

    public Account requirePostingAccount(String code) {
        Account account = get(code);
        account.requirePostingAllowed();
        return account;
    }

    public void rename(String code, String newName) {
        accounts.update(get(code).rename(newName));
    }

    public void deactivate(String code) {
        Account account = get(code);
        if (accounts.findChildren(account.getCode()).stream().anyMatch(Account::isActive)) {
            throw new IllegalStateException("Deactivate active children before their parent: " + code);
        }
        accounts.update(account.deactivate());
    }

    public void activate(String code) {
        Account account = get(code);
        validateParent(account);
        accounts.update(account.activate());
    }
}
