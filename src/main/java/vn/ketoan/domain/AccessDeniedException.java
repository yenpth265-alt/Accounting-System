package vn.ketoan.domain;

public final class AccessDeniedException extends SecurityException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
