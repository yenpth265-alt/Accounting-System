package vn.ketoan.infrastructure.jdbc;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.ketoan.application.service.DebtService;
import vn.ketoan.application.service.LedgerService;
import vn.ketoan.demo.JdbcDemo;
import vn.ketoan.domain.value.Money;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.PostingTestData.CLOCK;
import static vn.ketoan.WorkflowTestData.VIEWER;

/** Run explicitly with the disposable MySQL test configuration; SQL-client directives are MySQL specific. */
class MySqlSchemaInstallIT {

    @BeforeEach
    void requireMySqlConfiguration() {
        String config = System.getProperty("accounting.test.db.config");
        assertTrue(config != null && !config.isBlank(), "Set accounting.test.db.config to an isolated MySQL test configuration");
    }

    @Test
    void directSqlCreatesTheSameSchemaAndCanBeRepeatedWithoutChangingDataOrHistory() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            install(database, false);
            SchemaMigrator migrator = new SchemaMigrator(database.connections());
            assertEquals(0, migrator.migrate());
            JdbcDemo.seedCatalogs(database.transactions());
            try (PrintStream output = new PrintStream(new ByteArrayOutputStream())) {
                JdbcDemo.run(output, database.transactions(), CLOCK);
            }
            String history = history(database);
            install(database, false);
            assertEquals(history, history(database));
            assertEquals(0, migrator.migrate());
            assertEquals(2, new LedgerService(database.transactions()).entries(VIEWER).size());
            assertEquals(Money.of(6_000_000), new DebtService(database.transactions())
                    .statement("131", "KH01", java.time.LocalDate.of(2026, 11, 25), VIEWER).netDebt());
        }
    }

    @Test
    void interruptionDuringDirectSqlKeepsDirtyVersionAndCanBeResumedByTheApplication() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            assertThrows(SQLException.class, () -> install(database, true));
            try (Connection connection = database.connections().open(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT success FROM acc_schema_history WHERE version_no=2")) {
                assertTrue(rows.next());
                assertFalse(rows.getBoolean(1));
            }
            SchemaMigrator migrator = new SchemaMigrator(database.connections());
            assertThrows(IllegalStateException.class, migrator::migrate);
            assertEquals(3, migrator.migrate(true));
            assertEquals(0, migrator.migrate());
        }
    }

    @Test
    void directSqlCannotOverwriteAnIncompatibleMigrationChecksum() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            new SchemaMigrator(database.connections()).migrate();
            database.execute("UPDATE acc_schema_history SET checksum='changed' WHERE version_no=1");
            String history = history(database);
            assertThrows(SQLException.class, () -> install(database, false));
            assertEquals(history, history(database));
        }
    }

    @Test
    void directSqlRefusesUnversionedAccountingTablesAndPreservesTheirData() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            database.execute("CREATE TABLE acc_unknown (id INT PRIMARY KEY)");
            database.execute("INSERT INTO acc_unknown VALUES (7)");
            assertThrows(SQLException.class, () -> install(database, false));
            try (Connection connection = database.connections().open(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT id FROM acc_unknown")) {
                assertTrue(rows.next());
                assertEquals(7, rows.getInt(1));
            }
            assertThrows(IllegalStateException.class, () -> new SchemaMigrator(database.connections()).migrate());
        }
    }

    @Test
    void commandLineCanMigrateAndRepeatAClosingInAnIsolatedDatabase() throws Exception {
        try (JdbcTestDatabase database = new JdbcTestDatabase()) {
            assertTrue(command(database, "--db=migrate").contains("Migrations applied: 4"));
            assertTrue(command(database, "--demo=closing").contains("Period status=CLOSED"));
            assertTrue(command(database, "--demo=closing").contains("Closing decisions=2"));
            String reports = command(database, "--reports=2026-11");
            assertTrue(reports.contains("Revenue=10000000; expenses=3000000; profit=7000000"));
            assertTrue(reports.contains("Total assets=10000000; liabilities=3000000; equity=7000000"));
            assertTrue(command(database, "--db=check").contains("Closing batches=1; closing decisions=2"));
            assertEquals(5, new LedgerService(database.transactions()).entries(VIEWER).size());
            assertEquals(Money.of(-7_000_000), new LedgerService(database.transactions())
                    .netBalance("4212", java.time.LocalDate.of(2026, 11, 30), VIEWER));
        }
    }

    private String command(JdbcTestDatabase database, String option) throws Exception {
        Properties settings = new Properties();
        try (var input = Files.newBufferedReader(Path.of(System.getProperty("accounting.test.db.config")), StandardCharsets.UTF_8)) {
            settings.load(input);
        }
        String url;
        try (var connection = database.connections().open()) { url = connection.getMetaData().getURL(); }
        Path java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java");
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        ProcessBuilder builder = new ProcessBuilder(java.toString(), "-cp", classpath, "vn.ketoan.Main", option).redirectErrorStream(true);
        builder.environment().put("ACCOUNTING_DB_URL", url);
        builder.environment().put("ACCOUNTING_DB_USER", settings.getProperty("user"));
        builder.environment().put("ACCOUNTING_DB_PASSWORD", settings.getProperty("password"));
        Process process = builder.start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Isolated CLI test timed out");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private void install(JdbcTestDatabase database, boolean interrupt) throws SQLException {
        String script = SchemaSql.source().lines().filter(line -> !line.stripLeading().startsWith("--"))
                .collect(Collectors.joining("\n"));
        try (Connection connection = database.connections().open(); var statement = connection.createStatement()) {
            for (String sql : script.split(";")) {
                if (sql.isBlank()) { continue; }
                if (interrupt && sql.contains("CREATE TABLE IF NOT EXISTS acc_journal_lines")) {
                    throw new SQLException("Simulated SQL-client interruption", "HY000");
                }
                statement.execute(sql.strip());
            }
        }
    }

    private String history(JdbcTestDatabase database) throws SQLException {
        StringBuilder result = new StringBuilder();
        try (Connection connection = database.connections().open(); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT * FROM acc_schema_history ORDER BY version_no")) {
            while (rows.next()) {
                result.append(rows.getInt("version_no")).append('|').append(rows.getString("checksum"))
                        .append('|').append(rows.getBoolean("success")).append('|').append(rows.getString("started_at"))
                        .append('|').append(rows.getString("completed_at")).append('\n');
            }
        }
        return result.toString();
    }
}
