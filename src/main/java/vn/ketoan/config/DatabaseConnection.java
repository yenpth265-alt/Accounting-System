package vn.ketoan.config;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Properties;

import vn.ketoan.application.port.SqlConnectionFactory;

/** One new JDBC connection per transaction; callers own and close the returned connection. */
public final class DatabaseConnection implements SqlConnectionFactory {

    private final DatabaseSettings settings;

    public DatabaseConnection(DatabaseSettings settings) {
        this.settings = Objects.requireNonNull(settings, "Database settings are required");
    }

    @Override
    public Connection open() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", settings.getUsername());
        properties.setProperty("password", settings.getPassword());
        properties.setProperty("connectTimeout", "5000");
        properties.setProperty("socketTimeout", "30000");
        properties.setProperty("connectionTimeZone", "UTC");
        properties.setProperty("forceConnectionTimeZoneToSession", "true");
        properties.setProperty("allowMultiQueries", "false");
        properties.setProperty("useAffectedRows", "false");
        return DriverManager.getConnection(settings.getUrl(), properties);
    }
}
