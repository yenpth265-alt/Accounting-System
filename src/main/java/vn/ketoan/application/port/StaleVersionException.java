package vn.ketoan.application.port;

import java.util.UUID;

public final class StaleVersionException extends IllegalStateException {

    public StaleVersionException(UUID id, long expected, long actual) {
        super("Voucher " + id + " changed: expected version " + expected + ", current version " + actual);
    }
}
