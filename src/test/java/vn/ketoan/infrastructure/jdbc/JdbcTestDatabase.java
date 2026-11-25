package vn.ketoan.infrastructure.jdbc;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import vn.ketoan.application.port.SqlConnectionFactory;
import vn.ketoan.config.DatabaseConnection;
import vn.ketoan.config.DatabaseSettings;

/** Each test owns its complete database. An optional local config runs the same tests on real MySQL. */
final class JdbcTestDatabase implements AutoCloseable {

    private final SqlConnectionFactory connections;
    private final SqlConnectionFactory administrator;
    private final String ownedDatabase;

    JdbcTestDatabase() throws Exception {
        String configuration = System.getProperty("accounting.test.db.config");
        if (configuration == null || configuration.isBlank()) {
            String url = "jdbc:h2:mem:acc_" + UUID.randomUUID().toString().replace("-", "")
                    + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000";
            connections = () -> DriverManager.getConnection(url, "sa", "");
            administrator = null;
            ownedDatabase = null;
        } else {
            Properties values = new Properties();
            try (Reader reader = Files.newBufferedReader(Path.of(configuration), StandardCharsets.UTF_8)) {
                values.load(reader);
            }
            String url = values.getProperty("url");
            String user = values.getProperty("user");
            String password = values.getProperty("password");
            administrator = new DatabaseConnection(new DatabaseSettings(url, user, password));
            ownedDatabase = "accounting_it_" + UUID.randomUUID().toString().replace("-", "");
            try (Connection connection = administrator.open(); var statement = connection.createStatement()) {
                statement.execute("CREATE DATABASE `" + ownedDatabase + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
            }
            int query = url.indexOf('?');
            String base = query < 0 ? url : url.substring(0, query);
            String suffix = query < 0 ? "" : url.substring(query);
            String testUrl = base.substring(0, base.lastIndexOf('/') + 1) + ownedDatabase + suffix;
            connections = new DatabaseConnection(new DatabaseSettings(testUrl, user, password));
        }
    }

    SqlConnectionFactory connections() {
        return connections;
    }

    JdbcUnitOfWork transactions() {
        return new JdbcUnitOfWork(connections);
    }

    void migrateAndSeed() {
        new SchemaMigrator(connections).migrate();
        vn.ketoan.PostingTestData.seed(transactions());
    }

    void execute(String sql) throws SQLException {
        try (Connection connection = connections.open(); var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public void close() throws SQLException {
        if (administrator == null) {
            execute("SHUTDOWN");
        } else {
            if (!ownedDatabase.matches("accounting_it_[a-f0-9]{32}")) {
                throw new IllegalStateException("Refusing to remove a database not owned by this test");
            }
            try (Connection connection = administrator.open(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE `" + ownedDatabase + "`");
            }
        }
    }
}
