package vn.ketoan.domain;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** An immutable counterparty; a single party can be both customer and supplier. */
public final class Partner {

    private final String code;
    private final String name;
    private final Set<PartnerRole> roles;
    private final boolean active;

    public Partner(String code, String name, Set<PartnerRole> roles) {
        this(code, name, roles, true);
    }

    private Partner(String code, String name, Set<PartnerRole> roles, boolean active) {
        this.code = normalizeCode(code);
        this.name = DomainValidation.requiredText(name, 160, "Partner name");
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "Partner roles are required"));
        this.active = active;
        if (this.roles.isEmpty()) {
            throw new IllegalArgumentException("A partner must have at least one role");
        }
    }

    public static String normalizeCode(String code) {
        return DomainValidation.code(code, 32, "Partner code");
    }

    public Partner rename(String newName) {
        return new Partner(code, newName, roles, active);
    }

    public Partner addRole(PartnerRole role) {
        Objects.requireNonNull(role, "Partner role is required");
        Set<PartnerRole> newRoles = EnumSet.copyOf(roles);
        newRoles.add(role);
        return new Partner(code, name, newRoles, active);
    }

    public Partner deactivate() {
        return new Partner(code, name, roles, false);
    }

    public Partner activate() {
        return new Partner(code, name, roles, true);
    }

    public void requireRole(PartnerRole role) {
        Objects.requireNonNull(role, "Partner role is required");
        if (!active) {
            throw new IllegalStateException("Partner is inactive: " + code);
        }
        if (!roles.contains(role)) {
            throw new IllegalStateException("Partner " + code + " does not have role " + role);
        }
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public Set<PartnerRole> getRoles() {
        return roles;
    }

    public boolean isActive() {
        return active;
    }
}
