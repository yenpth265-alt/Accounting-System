package vn.ketoan.domain.report;

import java.util.Objects;

final class ReportText {
    private ReportText() { }
    static String required(String value, int maximum, String label) {
        String text = Objects.requireNonNull(value, label + " is required").strip();
        if (text.isEmpty() || text.length() > maximum) { throw new IllegalArgumentException(label + " has an invalid length"); }
        return text;
    }
}
