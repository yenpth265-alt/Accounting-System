package vn.ketoan.application.port;

import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import vn.ketoan.domain.AccountingPeriod;

public interface AccountingPeriodRepository {

    Optional<AccountingPeriod> findByMonth(YearMonth month);

    List<AccountingPeriod> findAll();

    void insert(AccountingPeriod period);

    void update(AccountingPeriod period);
}
