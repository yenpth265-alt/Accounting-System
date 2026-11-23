package vn.ketoan.domain;

import java.util.Objects;
import java.util.Optional;

/** An immutable chart-of-accounts definition, not an account balance. */
public final class Account {

    private final String code;
    private final String name;
    private final AccountType type;
    private final BalanceSide normalBalance;
    private final String parentCode;
    private final boolean postingAllowed;
    private final AccountTracking tracking;
    private final boolean active;

    private Account(String code, String name, AccountType type,
                    BalanceSide normalBalance, String parentCode,
                    boolean postingAllowed, AccountTracking tracking, boolean active) {
        this.code = normalizeCode(code);
        this.name = DomainValidation.requiredText(name, 120, "Account name");
        this.type = Objects.requireNonNull(type, "Account type is required");
        this.normalBalance = Objects.requireNonNull(normalBalance, "Normal balance is required");
        this.parentCode = parentCode == null ? null : normalizeCode(parentCode);
        this.postingAllowed = postingAllowed;
        this.tracking = Objects.requireNonNull(tracking, "Account tracking is required");
        this.active = active;
        if (this.code.equals(this.parentCode)) {
            throw new IllegalArgumentException("An account cannot be its own parent");
        }
        if (!postingAllowed && tracking != AccountTracking.NONE) {
            throw new IllegalArgumentException("A group account cannot require posting details");
        }
    }

    public static Account group(String code, String name, AccountType type,
                                BalanceSide normalBalance, String parentCode) {
        return new Account(code, name, type, normalBalance, parentCode,
                false, AccountTracking.NONE, true);
    }

    public static Account posting(String code, String name, AccountType type,
                                  BalanceSide normalBalance, String parentCode,
                                  AccountTracking tracking) {
        return new Account(code, name, type, normalBalance, parentCode, true, tracking, true);
    }

    public static String normalizeCode(String code) {
        return DomainValidation.code(code, 20, "Account code");
    }

    public Account rename(String newName) {
        return new Account(code, newName, type, normalBalance, parentCode,
                postingAllowed, tracking, active);
    }

    public Account deactivate() {
        return withActive(false);
    }

    public Account activate() {
        return withActive(true);
    }

    private Account withActive(boolean newActive) {
        return new Account(code, name, type, normalBalance, parentCode,
                postingAllowed, tracking, newActive);
    }

    public void requirePostingAllowed() {
        if (!active) {
            throw new IllegalStateException("Account is inactive: " + code);
        }
        if (!postingAllowed) {
            throw new IllegalStateException("Posting to a group account is not allowed: " + code);
        }
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public AccountType getType() {
        return type;
    }

    /** The usual balance side; it does not forbid an opposite-side actual balance. */
    public BalanceSide getNormalBalance() {
        return normalBalance;
    }

    public Optional<String> getParentCode() {
        return Optional.ofNullable(parentCode);
    }

    public boolean isPostingAllowed() {
        return postingAllowed;
    }

    public AccountTracking getTracking() {
        return tracking;
    }

    public boolean isActive() {
        return active;
    }
}
