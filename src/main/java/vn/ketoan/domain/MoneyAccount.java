package vn.ketoan.domain;

import java.util.Objects;

/** A cash fund or bank account linked to a posting account in the ledger. */
public final class MoneyAccount {

    private final String code;
    private final String name;
    private final String ledgerAccountCode;
    private final MoneyAccountKind kind;
    private final boolean active;

    public MoneyAccount(String code, String name, String ledgerAccountCode, MoneyAccountKind kind) {
        this(code, name, ledgerAccountCode, kind, true);
    }

    private MoneyAccount(String code, String name, String ledgerAccountCode,
                         MoneyAccountKind kind, boolean active) {
        this.code = normalizeCode(code);
        this.name = DomainValidation.requiredText(name, 120, "Money account name");
        this.ledgerAccountCode = Account.normalizeCode(ledgerAccountCode);
        this.kind = Objects.requireNonNull(kind, "Money account kind is required");
        this.active = active;
    }

    public static String normalizeCode(String code) {
        return DomainValidation.code(code, 32, "Money account code");
    }

    public MoneyAccount deactivate() {
        return new MoneyAccount(code, name, ledgerAccountCode, kind, false);
    }

    public MoneyAccount activate() {
        return new MoneyAccount(code, name, ledgerAccountCode, kind, true);
    }

    public void requireUsableWith(Account account) {
        Objects.requireNonNull(account, "Ledger account is required");
        if (!active) {
            throw new IllegalStateException("Money account is inactive: " + code);
        }
        account.requirePostingAllowed();
        if (!ledgerAccountCode.equals(account.getCode())) {
            throw new IllegalArgumentException("Money account " + code
                    + " belongs to ledger account " + ledgerAccountCode + ", not " + account.getCode());
        }
        if (account.getType() != AccountType.ASSET
                || account.getTracking() != AccountTracking.MONEY_ACCOUNT) {
            throw new IllegalArgumentException("Money accounts require a money-tracked asset account");
        }
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getLedgerAccountCode() {
        return ledgerAccountCode;
    }

    public MoneyAccountKind getKind() {
        return kind;
    }

    public boolean isActive() {
        return active;
    }
}
