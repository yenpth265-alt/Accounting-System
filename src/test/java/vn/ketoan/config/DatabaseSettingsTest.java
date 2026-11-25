package vn.ketoan.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DatabaseSettingsTest {

    @TempDir
    Path directory;

    @Test
    void environmentOverridesLocalFileAndPasswordWhitespaceIsPreserved() throws Exception {
        Path file = directory.resolve("database.local.properties");
        Files.writeString(file, "db.url=jdbc:mysql://localhost:3306/local\ndb.user=local\ndb.password=local\n");
        DatabaseSettings settings = DatabaseSettings.load(file, Map.of("ACCOUNTING_DB_USER", "user",
                "ACCOUNTING_DB_PASSWORD", " secret "));
        assertEquals("jdbc:mysql://localhost:3306/local", settings.getUrl());
        assertEquals("user", settings.getUsername());
        assertEquals(" secret ", settings.getPassword());
        assertFalse(settings.toString().contains("secret"));
    }

    @Test
    void missingCredentialsFailBeforeConnectingAndSecretsDoNotBelongInUrl() {
        assertThrows(IllegalArgumentException.class, () -> DatabaseSettings.load(directory.resolve("missing"), Map.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseSettings("jdbc:mysql://localhost/accounting?password=secret", "user", "secret"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseSettings("jdbc:mysql://user:secret@localhost/accounting", "user", "secret"));
        assertThrows(IllegalArgumentException.class,
                () -> new DatabaseSettings("jdbc:h2:mem:test", "user", "secret"));
    }
}
