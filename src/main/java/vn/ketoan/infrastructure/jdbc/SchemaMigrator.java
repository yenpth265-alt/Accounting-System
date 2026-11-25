package vn.ketoan.infrastructure.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import vn.ketoan.application.port.SqlConnectionFactory;

/** Explicit versioned migrations. Dirty markers survive MySQL's implicit DDL commits. */
public final class SchemaMigrator {

    private static final ReentrantLock LOCAL_LOCK = new ReentrantLock();
    private static final String MIGRATION_LOCK = "accounting_system_schema";
    private static final List<Migration> MIGRATIONS = List.of(
            migration(1, "Catalogs and vouchers", "V001"),
            migration(2, "Journal and allocations", "V002"),
            migration(3, "Corrections and prepaid expenses", "V003"));
    private final SqlConnectionFactory connections;

    public SchemaMigrator(SqlConnectionFactory connections) {
        this.connections = Objects.requireNonNull(connections, "Connection factory is required");
    }

    public int migrate() {
        return migrate(false);
    }

    /** Explicit recovery reruns only the unchanged, idempotent SQL of the last failed migration. */
    public int migrate(boolean resumeFailed) {
        LOCAL_LOCK.lock();
        try (Connection connection = connections.open()) {
            boolean mysql = isMySql(connection);
            if (mysql) { acquireMigrationLock(connection); }
            try {
                connection.setAutoCommit(true);
                if (!tableExists(connection, "acc_schema_history") && hasUnversionedTables(connection)) {
                    throw new IllegalStateException("Unversioned acc_ tables exist; inspect them before migrating");
                }
                executeScript(connection, SchemaSql.section("BOOTSTRAP"), mysql);
                Map<Integer, Applied> applied = applied(connection);
                if (applied.isEmpty() && hasUnversionedTables(connection)) {
                    throw new IllegalStateException("Unversioned acc_ tables exist; inspect them before migrating");
                }
                if (applied.size() > MIGRATIONS.size()) {
                    throw new IllegalStateException("Database schema is newer than this application");
                }
                for (int version = 1; version <= applied.size(); version++) {
                    Applied value = applied.get(version);
                    if (value == null || (!value.success() && version != applied.size())) {
                        throw new IllegalStateException("Migration history is not a contiguous sequence");
                    }
                }
                int count = 0;
                for (Migration migration : MIGRATIONS) {
                    Applied previous = applied.get(migration.version());
                    if (previous != null && !previous.checksum().equals(migration.checksum())) {
                        throw new IllegalStateException("Migration checksum mismatch at version " + migration.version());
                    }
                    if (previous != null && previous.success()) { continue; }
                    if (previous != null && !resumeFailed) {
                        throw new IllegalStateException("Migration " + migration.version()
                                + " is incomplete; inspect the database, then use --db=resume-migration");
                    }
                    markStarted(connection, migration, previous != null);
                    executeScript(connection, migration.script(), mysql);
                    try (PreparedStatement statement = connection.prepareStatement(
                            "UPDATE acc_schema_history SET success=?,completed_at=? WHERE version_no=?")) {
                        statement.setBoolean(1, true);
                        statement.setString(2, Instant.now().toString());
                        statement.setInt(3, migration.version());
                        statement.executeUpdate();
                    }
                    count++;
                }
                requireCurrent(connection);
                return count;
            } finally {
                if (mysql) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SELECT RELEASE_LOCK('" + MIGRATION_LOCK + "')");
                    }
                }
            }
        } catch (SQLException failure) {
            throw new JdbcPersistenceException(failure);
        } finally {
            LOCAL_LOCK.unlock();
        }
    }

    static void requireCurrent(Connection connection) throws SQLException {
        Map<Integer, Applied> values = applied(connection);
        if (values.size() != MIGRATIONS.size()) {
            throw new IllegalStateException("Schema version is incompatible; run --db=migrate with the matching application");
        }
        for (Migration migration : MIGRATIONS) {
            Applied value = values.get(migration.version());
            if (value == null || !value.success() || !value.checksum().equals(migration.checksum())) {
                throw new IllegalStateException("Schema is incomplete or its checksum changed; inspect migrations before use");
            }
        }
    }

    private static void markStarted(Connection connection, Migration migration, boolean resuming) throws SQLException {
        connection.setAutoCommit(false);
        try {
            if (tableExists(connection, "acc_transaction_lock")) { lockTransaction(connection); }
            if (!resuming) {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO acc_schema_history(version_no,description,checksum,success,started_at)
                        VALUES (?,?,?,?,?)
                        """)) {
                    statement.setInt(1, migration.version());
                    statement.setString(2, migration.description());
                    statement.setString(3, migration.checksum());
                    statement.setBoolean(4, false);
                    statement.setString(5, Instant.now().toString());
                    statement.executeUpdate();
                }
            }
            connection.commit();
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
            throw failure;
        } finally {
            connection.setAutoCommit(true);
        }
    }

    static void lockTransaction(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT id FROM acc_transaction_lock WHERE id=1 FOR UPDATE")) {
            if (!rows.next()) { throw new IllegalStateException("Transaction lock is missing; run --db=migrate"); }
        }
    }

    private static Map<Integer, Applied> applied(Connection connection) throws SQLException {
        Map<Integer, Applied> result = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version_no,checksum,success FROM acc_schema_history ORDER BY version_no")) {
            while (rows.next()) {
                result.put(rows.getInt(1), new Applied(rows.getString(2), rows.getBoolean(3)));
            }
        }
        return result;
    }

    private static boolean isMySql(Connection connection) throws SQLException {
        String product = connection.getMetaData().getDatabaseProductName();
        if (product.toLowerCase(Locale.ROOT).contains("mysql")) {
            int major = connection.getMetaData().getDatabaseMajorVersion();
            int minor = connection.getMetaData().getDatabaseMinorVersion();
            if (major < 8 || (major == 8 && minor < 4)) {
                throw new IllegalArgumentException("This schema requires MySQL 8.4 or later");
            }
            return true;
        }
        if (!product.equals("H2")) { throw new IllegalArgumentException("Unsupported JDBC database"); }
        return false;
    }

    private static void acquireMigrationLock(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT GET_LOCK('" + MIGRATION_LOCK + "',30)")) {
            if (!rows.next() || rows.getInt(1) != 1) {
                throw new IllegalStateException("Could not acquire the database migration lock");
            }
        }
    }

    private static void executeDdl(Connection connection, String sql, boolean mysql) throws SQLException {
        String statementSql = mysql && sql.toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")
                ? sql + " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" : sql;
        try (Statement statement = connection.createStatement()) { statement.execute(statementSql); }
    }

    private static void executeScript(Connection connection, String script, boolean mysql) throws SQLException {
        for (String sql : script.lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n")).split(";")) {
            if (!sql.isBlank()) { executeDdl(connection, sql.strip(), mysql); }
        }
    }

    private static boolean tableExists(Connection connection, String name) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) { if (tables.getString("TABLE_NAME").equalsIgnoreCase(name)) { return true; } }
        }
        return false;
    }

    private static boolean hasUnversionedTables(Connection connection) throws SQLException {
        try (ResultSet tables = connection.getMetaData().getTables(connection.getCatalog(), null, "%", new String[]{"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
                if (name.startsWith("acc_") && !name.equals("acc_schema_history")) { return true; }
            }
        }
        return false;
    }

    private static Migration migration(int version, String description, String section) {
        String script = SchemaSql.section(section);
        return new Migration(version, description, script, SchemaSql.checksum(script));
    }

    private record Migration(int version, String description, String script, String checksum) { }
    private record Applied(String checksum, boolean success) { }
}
