package vn.ketoan.infrastructure.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import vn.ketoan.domain.value.Money;

/** Shared connection scope, parameter binding and rollback-only state for one transaction. */
final class JdbcContext {

    private final Connection connection;
    private final Thread owner = Thread.currentThread();
    private boolean active = true;
    private boolean rollbackOnly;

    JdbcContext(Connection connection) {
        this.connection = connection;
    }

    void checkScope() {
        if (!active || Thread.currentThread() != owner) {
            throw new IllegalStateException("Repository access must stay inside its transaction and thread");
        }
    }

    <T> T guard(Supplier<T> operation) {
        return access(ignored -> operation.get());
    }

    void mutate(Runnable operation) {
        guard(() -> { operation.run(); return null; });
    }

    <T> List<T> query(String sql, RowMapper<T> mapper, Object... parameters) {
        return access(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                try (ResultSet rows = statement.executeQuery()) {
                    List<T> result = new ArrayList<>();
                    while (rows.next()) {
                        result.add(mapper.read(rows));
                    }
                    return List.copyOf(result);
                }
            }
        });
    }

    <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... parameters) {
        return guard(() -> {
            List<T> values = query(sql, mapper, parameters);
            if (values.size() > 1) {
                throw new IllegalStateException("A unique query returned more than one row");
            }
            return values.stream().findFirst();
        });
    }

    int update(String sql, Object... parameters) {
        return access(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                bind(statement, parameters);
                return statement.executeUpdate();
            }
        });
    }

    void abort() {
        rollbackOnly = true;
    }

    void requireCommittable() {
        checkScope();
        if (rollbackOnly) {
            throw new IllegalStateException("Transaction is rollback-only after a repository failure");
        }
    }

    void close() {
        active = false;
    }

    private <T> T access(SqlWork<T> operation) {
        checkScope();
        try {
            return operation.run(connection);
        } catch (SQLException failure) {
            rollbackOnly = true;
            throw new JdbcPersistenceException(failure);
        } catch (RuntimeException | Error failure) {
            rollbackOnly = true;
            throw failure;
        }
    }

    private static void bind(PreparedStatement statement, Object[] parameters) throws SQLException {
        for (int index = 0; index < parameters.length; index++) {
            Object value = parameters[index];
            int position = index + 1;
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else if (value instanceof Money money) {
                statement.setBigDecimal(position, money.toBigDecimal());
            } else if (value instanceof LocalDate date) {
                statement.setDate(position, java.sql.Date.valueOf(date));
            } else if (value instanceof Instant || value instanceof UUID || value instanceof Enum<?>) {
                statement.setString(position, value.toString());
            } else {
                statement.setObject(position, value);
            }
        }
    }

    @FunctionalInterface
    interface RowMapper<T> {
        T read(ResultSet row) throws SQLException;
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
