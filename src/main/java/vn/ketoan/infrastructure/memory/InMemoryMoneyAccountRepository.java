package vn.ketoan.infrastructure.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.domain.MoneyAccount;

/** Single-process demo storage, with the same immutable read semantics as other catalogs. */
public final class InMemoryMoneyAccountRepository implements MoneyAccountRepository {

    private final Map<String, MoneyAccount> accounts = new TreeMap<>();

    @Override
    public Optional<MoneyAccount> findByCode(String code) {
        return Optional.ofNullable(accounts.get(MoneyAccount.normalizeCode(code)));
    }

    @Override
    public List<MoneyAccount> findAll() {
        return List.copyOf(accounts.values());
    }

    @Override
    public void insert(MoneyAccount account) {
        Objects.requireNonNull(account, "Money account is required");
        if (accounts.putIfAbsent(account.getCode(), account) != null) {
            throw new IllegalStateException("Money account already exists: " + account.getCode());
        }
    }

    @Override
    public void update(MoneyAccount account) {
        Objects.requireNonNull(account, "Money account is required");
        if (accounts.replace(account.getCode(), account) == null) {
            throw new IllegalArgumentException("Money account not found: " + account.getCode());
        }
    }
}
