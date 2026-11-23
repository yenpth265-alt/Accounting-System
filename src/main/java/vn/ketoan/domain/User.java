package vn.ketoan.domain;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** An immutable authorization identity. Authentication is handled outside this model. */
public final class User {

    private final long id;
    private final String username;
    private final String displayName;
    private final Set<UserRole> roles;
    private final boolean active;

    public User(long id, String username, String displayName, Set<UserRole> roles) {
        this(id, username, displayName, roles, true);
    }

    private User(long id, String username, String displayName, Set<UserRole> roles, boolean active) {
        if (id <= 0) {
            throw new IllegalArgumentException("User id must be positive");
        }
        this.id = id;
        this.username = DomainValidation.code(username, 64, "Username").toLowerCase(Locale.ROOT);
        this.displayName = DomainValidation.requiredText(displayName, 80, "Display name");
        this.roles = Set.copyOf(Objects.requireNonNull(roles, "User roles are required"));
        this.active = active;
        if (this.roles.isEmpty()) {
            throw new IllegalArgumentException("A user must have at least one role");
        }
    }

    public User deactivate() {
        return new User(id, username, displayName, roles, false);
    }

    public User activate() {
        return new User(id, username, displayName, roles, true);
    }

    public boolean hasRole(UserRole role) {
        return roles.contains(Objects.requireNonNull(role, "Role is required"));
    }

    public void requirePermission(Permission permission) {
        Objects.requireNonNull(permission, "Permission is required");
        if (!active || roles.stream().noneMatch(role -> role.grants(permission))) {
            throw new AccessDeniedException("User " + username + " is not allowed to " + permission);
        }
    }

    public long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public Set<UserRole> getRoles() {
        return roles;
    }

    public boolean isActive() {
        return active;
    }
}
