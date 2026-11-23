package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/** Draft input may be incomplete or unbalanced; submission validation is separate. */
public final class Draft {

    private final String reference;
    private final LocalDate documentDate;
    private final LocalDate postingDate;
    private final String description;
    private final List<VoucherLine> lines;

    public Draft(String reference, LocalDate documentDate, LocalDate postingDate,
                 String description, List<VoucherLine> lines) {
        this.reference = DomainValidation.code(reference, 32, "Draft reference");
        this.documentDate = requireDate(documentDate, "Document date");
        this.postingDate = requireDate(postingDate, "Posting date");
        this.description = DomainValidation.requiredText(description, 255, "Voucher description");
        this.lines = List.copyOf(Objects.requireNonNull(lines, "Voucher lines are required"));
    }

    private static LocalDate requireDate(LocalDate date, String label) {
        Objects.requireNonNull(date, label + " is required");
        if (date.getYear() < 1000 || date.getYear() > 9999) {
            throw new IllegalArgumentException(label + " must be within years 1000 to 9999");
        }
        return date;
    }

    public Draft withLines(List<VoucherLine> newLines) {
        return new Draft(reference, documentDate, postingDate, description, newLines);
    }

    public String getReference() {
        return reference;
    }

    public LocalDate getDocumentDate() {
        return documentDate;
    }

    public LocalDate getPostingDate() {
        return postingDate;
    }

    public String getDescription() {
        return description;
    }

    public List<VoucherLine> getLines() {
        return lines;
    }
}
