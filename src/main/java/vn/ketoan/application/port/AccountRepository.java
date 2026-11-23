package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;

import vn.ketoan.domain.Account;

public interface AccountRepository {

    Optional<Account> findByCode(String code);

    List<Account> findAll();

    List<Account> findChildren(String parentCode);

    void insert(Account account);

    void update(Account account);
}
