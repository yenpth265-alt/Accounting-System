package vn.ketoan.infrastructure.jdbc;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import vn.ketoan.application.port.SqlConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaMigratorTest {

    @Test
    void packagedSchemaMatchesTheOnlySourceAndInstallerChecksumsMatchMigrationSections() throws Exception {
        String source = SchemaSql.source();
        assertEquals(Files.readString(Path.of("sql/01_schema.sql")).replace("\r\n", "\n"), source);
        for (String section : new String[]{"V001", "V002", "V003"}) {
            var declaration = Pattern.compile("SET @acc_" + section.toLowerCase()
                    + "_checksum = '([a-f0-9]{64})';").matcher(source);
            assertTrue(declaration.find());
            assertEquals(SchemaSql.checksum(SchemaSql.section(section)), declaration.group(1));
        }
        assertEquals(21, Pattern.compile("(?m)^CREATE TABLE IF NOT EXISTS acc_").matcher(source).results().count());
    }

    @Test
    void appliesEachVersionOnceAndPreservesLegacyTables() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            database.execute("CREATE TABLE legacy_marker (id INT PRIMARY KEY)");
            database.execute("INSERT INTO legacy_marker VALUES (7)");
            SchemaMigrator migrator = new SchemaMigrator(database.connections());
            assertEquals(3, migrator.migrate());
            assertEquals(0, migrator.migrate());
            database.transactions().execute(session -> {
                assertEquals(0, session.accounts().findAll().size());
                return null;
            });
            try (Connection connection = database.connections().open(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id FROM legacy_marker")) {
                rows.next();
                assertEquals(7, rows.getInt(1));
            }
        }
    }

    @Test
    void checksumChangesBlockMigrationAndApplicationTransactions() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            new SchemaMigrator(database.connections()).migrate();
            database.execute("UPDATE acc_schema_history SET checksum='changed' WHERE version_no=1");
            assertThrows(IllegalStateException.class, () -> new SchemaMigrator(database.connections()).migrate());
            AtomicBoolean callback = new AtomicBoolean();
            assertThrows(IllegalStateException.class, () -> database.transactions().execute(session -> {
                callback.set(true);
                return null;
            }));
            assertFalse(callback.get());
        }
    }

    @Test
    void interruptedDdlKeepsDirtyMarkerAndNeedsExplicitResume() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            SqlConnectionFactory failing = failOnceDuringJournalCreation(database.connections());
            assertThrows(JdbcPersistenceException.class, () -> new SchemaMigrator(failing).migrate());
            try (Connection connection = database.connections().open(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT success FROM acc_schema_history WHERE version_no=2")) {
                rows.next();
                assertFalse(rows.getBoolean(1));
            }
            assertThrows(IllegalStateException.class, () -> new SchemaMigrator(database.connections()).migrate());
            assertThrows(IllegalStateException.class, () -> database.transactions().execute(session -> null));
            assertEquals(2, new SchemaMigrator(database.connections()).migrate(true));
            assertEquals(0, new SchemaMigrator(database.connections()).migrate());
            assertEquals(0, database.transactions().<Integer>execute(session -> session.journal().findAll().size()));
        }
    }

    @Test
    void unversionedAccountingTablesAreNotSilentlyAdopted() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            database.execute("CREATE TABLE acc_unknown (id INT PRIMARY KEY)");
            assertThrows(IllegalStateException.class, () -> new SchemaMigrator(database.connections()).migrate());
        }
    }

    @Test
    void anEmptyHistoryTableDoesNotPermitAdoptingUnknownAccountingTables() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            database.execute(SchemaSql.section("BOOTSTRAP").strip());
            database.execute("CREATE TABLE acc_unknown (id INT PRIMARY KEY)");
            assertThrows(IllegalStateException.class, () -> new SchemaMigrator(database.connections()).migrate());
        }
    }

    private SqlConnectionFactory failOnceDuringJournalCreation(SqlConnectionFactory original) {
        AtomicBoolean fail = new AtomicBoolean(true);
        return () -> {
            Connection delegate = original.open();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        Object result = invoke(delegate, method, arguments);
                        if (method.getName().equals("createStatement")) {
                            Statement statement = (Statement) result;
                            return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Statement.class},
                                    (statementProxy, statementMethod, statementArguments) -> {
                                        if (statementMethod.getName().equals("execute") && statementArguments[0] instanceof String sql
                                                && sql.contains("CREATE TABLE IF NOT EXISTS acc_journal_lines") && fail.getAndSet(false)) {
                                            throw new SQLException("Simulated interruption during DDL", "HY000");
                                        }
                                        return invoke(statement, statementMethod, statementArguments);
                                    });
                        }
                        return result;
                    });
        };
    }

    private Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try { return method.invoke(target, arguments); }
        catch (InvocationTargetException failure) { throw failure.getCause(); }
    }
}
