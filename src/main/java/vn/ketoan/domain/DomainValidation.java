package vn.ketoan.domain;

import java.util.Locale;
import java.util.Objects;

final class DomainValidation {

    private DomainValidation() {
    }

    static String requiredText(String value, int maxLength, String label) {
        Objects.requireNonNull(value, label + " is required");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(
                    label + " must contain between 1 and " + maxLength + " characters");
        }
        return normalized;
    }

    static String code(String value, int maxLength, String label) {
        String normalized = requiredText(value, maxLength, label);
        if (!normalized.matches("[A-Za-z0-9][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(
                    label + " must start with a letter or digit and use only letters, digits, '.', '_' or '-'");
        }
        return normalized.toUpperCase(Locale.ROOT);
    }
}
