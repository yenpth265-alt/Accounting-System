package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import vn.ketoan.application.port.UserRepository;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;

final class JdbcUserRepository implements UserRepository {

    private final JdbcContext context;

    JdbcUserRepository(JdbcContext context) {
        this.context = context;
    }

    @Override
    public Optional<User> findById(long id) {
        return context.guard(() -> {
            if (id <= 0) { throw new IllegalArgumentException("User id must be positive"); }
            return context.one("SELECT * FROM acc_users WHERE id=?", this::read, id);
        });
    }

    @Override
    public List<User> findAll() {
        return context.query("SELECT * FROM acc_users ORDER BY id", this::read);
    }

    @Override
    public void insert(User value) {
        context.mutate(() -> {
            context.update("INSERT INTO acc_users(id,username,display_name,active) VALUES (?,?,?,?)",
                    value.getId(), value.getUsername(), value.getDisplayName(), value.isActive());
            insertRoles(value);
        });
    }

    @Override
    public void update(User value) {
        context.mutate(() -> {
            findById(value.getId()).orElseThrow(() -> new IllegalArgumentException("User not found: " + value.getId()));
            context.update("UPDATE acc_users SET username=?,display_name=?,active=? WHERE id=?",
                    value.getUsername(), value.getDisplayName(), value.isActive(), value.getId());
            context.update("DELETE FROM acc_user_roles WHERE user_id=?", value.getId());
            insertRoles(value);
        });
    }

    private void insertRoles(User value) {
        value.getRoles().stream().sorted().forEach(role -> context.update(
                "INSERT INTO acc_user_roles(user_id,role_code) VALUES (?,?)", value.getId(), role));
    }

    private User read(ResultSet row) throws SQLException {
        long id = row.getLong("id");
        Set<UserRole> roles = Set.copyOf(context.query("SELECT role_code FROM acc_user_roles WHERE user_id=? ORDER BY role_code",
                value -> UserRole.valueOf(value.getString("role_code")), id));
        User user = new User(id, row.getString("username"), row.getString("display_name"), roles);
        return row.getBoolean("active") ? user : user.deactivate();
    }
}
