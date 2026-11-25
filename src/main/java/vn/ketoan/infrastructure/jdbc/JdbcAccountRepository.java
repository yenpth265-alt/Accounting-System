package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.BalanceSide;

final class JdbcAccountRepository implements AccountRepository {

    private final JdbcContext context;

    JdbcAccountRepository(JdbcContext context) {
        this.context = context;
    }

    @Override
    public Optional<Account> findByCode(String code) {
        return context.guard(() -> context.one("SELECT * FROM acc_accounts WHERE code=?", this::read, Account.normalizeCode(code)));
    }

    @Override
    public List<Account> findAll() {
        return context.query("SELECT * FROM acc_accounts ORDER BY code", this::read);
    }

    @Override
    public List<Account> findChildren(String code) {
        return context.guard(() -> context.query("SELECT * FROM acc_accounts WHERE parent_code=? ORDER BY code",
                this::read, Account.normalizeCode(code)));
    }

    @Override
    public void insert(Account value) {
        context.mutate(() -> context.update("""
                INSERT INTO acc_accounts(code,name,account_type,normal_balance,parent_code,posting_allowed,tracking,active)
                VALUES (?,?,?,?,?,?,?,?)
                """, value.getCode(), value.getName(), value.getType(), value.getNormalBalance(),
                value.getParentCode().orElse(null), value.isPostingAllowed(), value.getTracking(), value.isActive()));
    }

    @Override
    public void update(Account value) {
        context.mutate(() -> {
            findByCode(value.getCode()).orElseThrow(() -> new IllegalArgumentException("Account not found: " + value.getCode()));
            context.update("""
                    UPDATE acc_accounts SET name=?,account_type=?,normal_balance=?,parent_code=?,posting_allowed=?,tracking=?,active=?
                    WHERE code=?
                    """, value.getName(), value.getType(), value.getNormalBalance(), value.getParentCode().orElse(null),
                    value.isPostingAllowed(), value.getTracking(), value.isActive(), value.getCode());
        });
    }

    private Account read(ResultSet row) throws SQLException {
        String code = row.getString("code");
        String name = row.getString("name");
        AccountType type = AccountType.valueOf(row.getString("account_type"));
        BalanceSide side = BalanceSide.valueOf(row.getString("normal_balance"));
        String parent = row.getString("parent_code");
        Account account = row.getBoolean("posting_allowed")
                ? Account.posting(code, name, type, side, parent, AccountTracking.valueOf(row.getString("tracking")))
                : Account.group(code, name, type, side, parent);
        return row.getBoolean("active") ? account : account.deactivate();
    }
}
