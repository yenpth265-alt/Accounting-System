package vn.ketoan.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Connection secrets are read locally and are never included in a string representation. */
public final class DatabaseSettings {

    private final String url;
    private final String username;
    private final String password;

    public DatabaseSettings(String url, String username, String password) {
        this.url = Objects.requireNonNull(url, "Database URL is required").strip();
        this.username = Objects.requireNonNull(username, "Database username is required").strip();
        this.password = Objects.requireNonNull(password, "Database password is required");
        String lower = this.url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("jdbc:mysql://") || this.username.isEmpty()
                || lower.matches(".*[?&](user|password)=.*") || lower.contains("@")) {
            throw new IllegalArgumentException("Use a jdbc:mysql:// URL and provide credentials in separate settings");
        }
    }

    public static DatabaseSettings load(Path file, Map<String, String> environment) {
        Objects.requireNonNull(file, "Configuration path is required");
        Objects.requireNonNull(environment, "Environment is required");
        Properties values = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                values.load(reader);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot read database configuration", failure);
            }
        }
        return new DatabaseSettings(setting(environment, values, "URL", "url"),
                setting(environment, values, "USER", "user"), setting(environment, values, "PASSWORD", "password"));
    }

    private static String setting(Map<String, String> environment, Properties values, String suffix, String key) {
        String result = environment.getOrDefault("ACCOUNTING_DB_" + suffix, values.getProperty("db." + key));
        if (result == null) {
            throw new IllegalArgumentException("Missing ACCOUNTING_DB_" + suffix + " or db." + key
                    + "; configure database.local.properties using database.properties.example");
        }
        return result;
    }

    public String getUrl() {
        return url;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }
}
