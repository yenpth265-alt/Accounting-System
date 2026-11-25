package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.MoneyAccountKind;

final class JdbcMoneyAccountRepository implements MoneyAccountRepository {

    private final JdbcContext context;

    JdbcMoneyAccountRepository(JdbcContext context) {
        this.context = context;
    }

    @Override
    public Optional<MoneyAccount> findByCode(String code) {
        return context.guard(() -> context.one("SELECT * FROM acc_money_accounts WHERE code=?", this::read, MoneyAccount.normalizeCode(code)));
    }

    @Override
    public List<MoneyAccount> findAll() {
        return context.query("SELECT * FROM acc_money_accounts ORDER BY code", this::read);
    }

    @Override
    public void insert(MoneyAccount value) {
        context.mutate(() -> context.update("INSERT INTO acc_money_accounts(code,name,ledger_account_code,kind,active) VALUES (?,?,?,?,?)",
                value.getCode(), value.getName(), value.getLedgerAccountCode(), value.getKind(), value.isActive()));
    }

    @Override
    public void update(MoneyAccount value) {
        context.mutate(() -> {
            findByCode(value.getCode()).orElseThrow(() -> new IllegalArgumentException("Money account not found: " + value.getCode()));
            context.update("UPDATE acc_money_accounts SET name=?,ledger_account_code=?,kind=?,active=? WHERE code=?",
                    value.getName(), value.getLedgerAccountCode(), value.getKind(), value.isActive(), value.getCode());
        });
    }

    private MoneyAccount read(ResultSet row) throws SQLException {
        MoneyAccount account = new MoneyAccount(row.getString("code"), row.getString("name"),
                row.getString("ledger_account_code"), MoneyAccountKind.valueOf(row.getString("kind")));
        return row.getBoolean("active") ? account : account.deactivate();
    }
}
