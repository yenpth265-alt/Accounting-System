package vn.ketoan.domain;

import java.util.Set;

public enum UserRole {
    ACCOUNTANT(Set.of(Permission.VIEW_VOUCHERS, Permission.CREATE_VOUCHER,
            Permission.EDIT_VOUCHER, Permission.SUBMIT_VOUCHER, Permission.CANCEL_VOUCHER)),
    CHIEF(Set.of(Permission.VIEW_VOUCHERS, Permission.REVIEW_VOUCHER,
            Permission.WITHDRAW_APPROVAL, Permission.CANCEL_VOUCHER)),
    VIEWER(Set.of(Permission.VIEW_VOUCHERS)),
    ADMIN(Set.of());

    private final Set<Permission> permissions;

    UserRole(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public boolean grants(Permission permission) {
        return permissions.contains(permission);
    }
}
