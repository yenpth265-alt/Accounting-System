package vn.ketoan.infrastructure.jdbc;

import java.sql.SQLException;

public final class JdbcPersistenceException extends IllegalStateException {

    public JdbcPersistenceException(SQLException cause) {
        super("Database operation failed (SQLState " + cause.getSQLState() + ")", cause);
    }
}
