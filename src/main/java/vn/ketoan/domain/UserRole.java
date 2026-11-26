package vn.ketoan.domain;

import java.util.Set;

public enum UserRole {
    ACCOUNTANT(Set.of(Permission.VIEW_VOUCHERS, Permission.CREATE_VOUCHER,
            Permission.EDIT_VOUCHER, Permission.SUBMIT_VOUCHER, Permission.CANCEL_VOUCHER, Permission.PREPARE_CLOSING,
            Permission.CLASSIFY_CASH_FLOW, Permission.SAVE_REPORT_VERSION)),
    CHIEF(Set.of(Permission.VIEW_VOUCHERS, Permission.REVIEW_VOUCHER,
            Permission.WITHDRAW_APPROVAL, Permission.POST_VOUCHER, Permission.CANCEL_VOUCHER,
            Permission.ALLOCATE_DEBT, Permission.CANCEL_DEBT_ALLOCATION, Permission.CLOSE_PERIOD,
            Permission.CLASSIFY_CASH_FLOW, Permission.PUBLISH_REPORT)),
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
