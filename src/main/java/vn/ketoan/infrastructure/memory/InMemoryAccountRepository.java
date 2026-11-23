package vn.ketoan.infrastructure.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.domain.Account;

/** Single-process demo storage. Cross-entity transactions are not provided here. */
public final class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> accounts = new TreeMap<>();

    @Override
    public Optional<Account> findByCode(String code) {
        return Optional.ofNullable(accounts.get(Account.normalizeCode(code)));
    }

    @Override
    public List<Account> findAll() {
        return List.copyOf(accounts.values());
    }

    @Override
    public List<Account> findChildren(String parentCode) {
        String normalized = Account.normalizeCode(parentCode);
        return accounts.values().stream()
                .filter(account -> account.getParentCode().filter(normalized::equals).isPresent())
                .toList();
    }

    @Override
    public void insert(Account account) {
        Objects.requireNonNull(account, "Account is required");
        if (accounts.putIfAbsent(account.getCode(), account) != null) {
            throw new IllegalStateException("Account already exists: " + account.getCode());
        }
    }

    @Override
    public void update(Account account) {
        Objects.requireNonNull(account, "Account is required");
        if (accounts.replace(account.getCode(), account) == null) {
            throw new IllegalArgumentException("Account not found: " + account.getCode());
        }
    }
}
