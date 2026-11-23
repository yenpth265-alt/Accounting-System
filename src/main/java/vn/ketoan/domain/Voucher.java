package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import vn.ketoan.domain.value.Money;

/** Immutable voucher content; approval and posting are separate workflow use cases. */
public abstract class Voucher {

    private final VoucherType type;
    private final Draft draft;

    protected Voucher(VoucherType type, Draft draft) {
        this.type = Objects.requireNonNull(type, "Voucher type is required");
        this.draft = Objects.requireNonNull(draft, "Draft is required");
    }

    /** Template Method: common checks always precede the subtype's business rules. */
    public final void validateForSubmission(VoucherValidationContext context) {
        Objects.requireNonNull(context, "Validation context is required");
        context.getPeriod().requireOpenForPosting(getPostingDate());
        if (getLines().size() < 2) {
            throw new IllegalArgumentException("A voucher requires at least two lines");
        }
        if (!getTotalDebit().isPositive() || !getTotalDebit().equals(getTotalCredit())) {
            throw new IllegalArgumentException("Voucher debits and credits must balance");
        }
        for (VoucherLine line : getLines()) {
            validateLine(line, context);
        }
        validateSpecific(context);
    }

    private void validateLine(VoucherLine line, VoucherValidationContext context) {
        Account account = context.requireAccount(line.getAccountCode());
        account.requirePostingAllowed();
        if (account.getTracking() == AccountTracking.PARTNER && line.getPartnerCode().isEmpty()) {
            throw new IllegalArgumentException("Partner is required for account " + account.getCode());
        }
        line.getPartnerCode().ifPresent(code -> {
            if (!context.requirePartner(code).isActive()) {
                throw new IllegalStateException("Partner is inactive: " + code);
            }
        });
        if (account.getTracking() == AccountTracking.MONEY_ACCOUNT && line.getMoneyAccountCode().isEmpty()) {
            throw new IllegalArgumentException("Money account is required for account " + account.getCode());
        }
        line.getMoneyAccountCode().ifPresent(code -> {
            if (account.getTracking() != AccountTracking.MONEY_ACCOUNT) {
                throw new IllegalArgumentException("Unexpected money account on ledger account " + account.getCode());
            }
            context.requireMoneyAccount(code).requireUsableWith(account);
        });
    }

    protected abstract void validateSpecific(VoucherValidationContext context);

    protected final void requireNoMoneyMovements(VoucherValidationContext context) {
        if (getLines().stream().anyMatch(line -> context.requireAccount(line.getAccountCode())
                .getTracking() == AccountTracking.MONEY_ACCOUNT)) {
            throw new IllegalArgumentException("Use a separate receipt or payment for settlement");
        }
    }

    public final VoucherType getType() {
        return type;
    }

    public final String getReference() {
        return draft.getReference();
    }

    public final LocalDate getDocumentDate() {
        return draft.getDocumentDate();
    }

    public final LocalDate getPostingDate() {
        return draft.getPostingDate();
    }

    public final String getDescription() {
        return draft.getDescription();
    }

    public final List<VoucherLine> getLines() {
        return draft.getLines();
    }

    public final Money getTotalDebit() {
        return getLines().stream().map(VoucherLine::getDebit).reduce(Money.ZERO, Money::add);
    }

    public final Money getTotalCredit() {
        return getLines().stream().map(VoucherLine::getCredit).reduce(Money.ZERO, Money::add);
    }
}
