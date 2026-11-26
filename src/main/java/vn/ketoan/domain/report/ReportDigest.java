package vn.ketoan.domain.report;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class ReportDigest {
    private final MessageDigest digest;
    ReportDigest() {
        try { digest = MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
    void add(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }
    String finish() { return HexFormat.of().formatHex(digest.digest()); }
}
