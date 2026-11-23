package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;

import vn.ketoan.domain.MoneyAccount;

public interface MoneyAccountRepository {

    Optional<MoneyAccount> findByCode(String code);

    List<MoneyAccount> findAll();

    void insert(MoneyAccount account);

    void update(MoneyAccount account);
}
