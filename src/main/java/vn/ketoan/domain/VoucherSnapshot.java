package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import vn.ketoan.domain.value.Money;

/** Value-based audit content, independent of live catalogs and object identity. */
public record VoucherSnapshot(VoucherType type, String reference, LocalDate documentDate,
                              LocalDate postingDate, String description, List<Line> lines) {

    public VoucherSnapshot {
        Objects.requireNonNull(type, "Voucher type is required");
        Objects.requireNonNull(reference, "Reference is required");
        Objects.requireNonNull(documentDate, "Document date is required");
        Objects.requireNonNull(postingDate, "Posting date is required");
        Objects.requireNonNull(description, "Description is required");
        lines = List.copyOf(Objects.requireNonNull(lines, "Snapshot lines are required"));
    }

    public static VoucherSnapshot from(Voucher voucher) {
        Objects.requireNonNull(voucher, "Voucher is required");
        List<Line> lines = voucher.getLines().stream().map(line -> new Line(line.getAccountCode(),
                line.getSide(), line.getAmount(), line.getPartnerCode().orElse(null),
                line.getMoneyAccountCode().orElse(null))).toList();
        return new VoucherSnapshot(voucher.getType(), voucher.getReference(), voucher.getDocumentDate(),
                voucher.getPostingDate(), voucher.getDescription(), lines);
    }

    /** Rebuilds the concrete subtype without checking current catalogs or reopening a historical period. */
    public Voucher toVoucher() {
        Draft draft = new Draft(reference, documentDate, postingDate, description, lines.stream()
                .map(line -> new VoucherLine(line.accountCode(), line.side(), line.amount(),
                        line.partnerCode(), line.moneyAccountCode())).toList());
        VoucherCreator creator = switch (type) {
            case REVENUE -> new RevenueCreator();
            case EXPENSE -> new ExpenseCreator();
            case RECEIPT -> new ReceiptCreator();
            case PAYMENT -> new PaymentCreator();
            case GENERAL -> new GeneralCreator();
        };
        return creator.create(draft);
    }

    public record Line(String accountCode, BalanceSide side, Money amount,
                       String partnerCode, String moneyAccountCode) {

        public Line {
            Objects.requireNonNull(accountCode, "Account code is required");
            Objects.requireNonNull(side, "Side is required");
            Objects.requireNonNull(amount, "Amount is required");
        }
    }
}
