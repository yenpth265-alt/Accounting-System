package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import vn.ketoan.domain.value.Money;

/** Complete recognition input for one counterparty. It generates the balancing debt line. */
public final class RecognitionDraft {

    private final Draft header;
    private final String partnerCode;
    private final String debtAccountCode;
    private final List<RecognitionItem> items;
    private final Money total;

    public RecognitionDraft(String reference, LocalDate documentDate, LocalDate postingDate,
                            String description, String partnerCode, String debtAccountCode,
                            List<RecognitionItem> items) {
        this.header = new Draft(reference, documentDate, postingDate, description, List.of());
        this.partnerCode = Partner.normalizeCode(partnerCode);
        this.debtAccountCode = Account.normalizeCode(debtAccountCode);
        this.items = List.copyOf(Objects.requireNonNull(items, "Recognition items are required"));
        if (this.items.isEmpty()) {
            throw new IllegalArgumentException("At least one recognition item is required");
        }
        this.total = this.items.stream().map(RecognitionItem::getAmount).reduce(Money.ZERO, Money::add);
        if (total.toBigDecimal().precision() > 19) {
            throw new IllegalArgumentException("The total debt amount must fit DECIMAL(19,0)");
        }
    }

    /** Receivable debit equals all revenue and other credit components combined. */
    public Draft toRevenueDraft() {
        List<VoucherLine> lines = new ArrayList<>();
        lines.add(VoucherLine.debit(debtAccountCode, total, partnerCode, null));
        for (RecognitionItem item : items) {
            lines.add(VoucherLine.credit(item.getAccountCode(), item.getAmount(), null, null));
        }
        return header.withLines(lines);
    }

    /** Payable credit equals all expense and other debit components combined. */
    public Draft toExpenseDraft() {
        List<VoucherLine> lines = new ArrayList<>();
        for (RecognitionItem item : items) {
            lines.add(VoucherLine.debit(item.getAccountCode(), item.getAmount(), null, null));
        }
        lines.add(VoucherLine.credit(debtAccountCode, total, partnerCode, null));
        return header.withLines(lines);
    }

    public String getReference() {
        return header.getReference();
    }

    public LocalDate getDocumentDate() {
        return header.getDocumentDate();
    }

    public LocalDate getPostingDate() {
        return header.getPostingDate();
    }

    public String getDescription() {
        return header.getDescription();
    }

    public String getPartnerCode() {
        return partnerCode;
    }

    public String getDebtAccountCode() {
        return debtAccountCode;
    }

    public List<RecognitionItem> getItems() {
        return items;
    }

    public Money getTotal() {
        return total;
    }
}
