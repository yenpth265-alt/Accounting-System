package vn.ketoan.infrastructure.jdbc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Reads the canonical sql/01_schema.sql copied into the classpath by Maven. */
final class SchemaSql {

    private static final String SOURCE = readSource();

    private SchemaSql() {
    }

    static String source() {
        return SOURCE;
    }

    static String section(String name) {
        String begin = "-- BEGIN " + name + "\n";
        String end = "-- END " + name + "\n";
        int start = SOURCE.indexOf(begin);
        int finish = start < 0 ? -1 : SOURCE.indexOf(end, start + begin.length());
        if (start < 0 || finish < 0 || SOURCE.indexOf(begin, start + begin.length()) >= 0
                || SOURCE.indexOf(end, finish + end.length()) >= 0) {
            throw new IllegalStateException("Missing or duplicate schema section: " + name);
        }
        String sql = SOURCE.substring(start + begin.length(), finish);
        if (sql.isBlank()) { throw new IllegalStateException("Schema section is empty: " + name); }
        return sql;
    }

    static String checksum(String script) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(script.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String readSource() {
        try (var input = SchemaSql.class.getResourceAsStream("/db/01_schema.sql")) {
            if (input == null) { throw new IllegalStateException("Missing canonical schema: /db/01_schema.sql"); }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read the canonical schema", failure);
        }
    }
}
