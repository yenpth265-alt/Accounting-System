package vn.ketoan.infrastructure.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Function;

import vn.ketoan.application.model.AccountingSnapshot;
import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.SqlConnectionFactory;
import vn.ketoan.application.port.UnitOfWork;

/** A database row serializes transactions across connections, threads and application processes. */
public final class JdbcUnitOfWork implements UnitOfWork {

    private static final ThreadLocal<JdbcAccountingSession> CURRENT = new ThreadLocal<>();
    private final SqlConnectionFactory connections;

    public JdbcUnitOfWork(SqlConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "Connection factory is required");
    }

    @Override
    public <T> T execute(Function<AccountingSession, T> work) {
        Objects.requireNonNull(work, "Transaction callback is required");
        if (CURRENT.get() != null) {
            CURRENT.get().abort();
            throw new IllegalStateException("Nested transactions are not supported");
        }
        try (Connection connection = connections.open()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setAutoCommit(false);
            JdbcAccountingSession session = new JdbcAccountingSession(connection);
            CURRENT.set(session);
            try {
                SchemaMigrator.lockTransaction(connection);
                SchemaMigrator.requireCurrent(connection);
                T result = work.apply(session);
                session.requireCommittable();
                AccountingSnapshot.capture(session).validateIntegrity();
                session.requireCommittable();
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                throw failure;
            } finally {
                session.close();
                CURRENT.remove();
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException(failure);
        }
    }
}
