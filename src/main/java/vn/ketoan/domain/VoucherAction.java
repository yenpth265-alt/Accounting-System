package vn.ketoan.domain;

public enum VoucherAction {
    CREATED(Permission.CREATE_VOUCHER),
    EDITED(Permission.EDIT_VOUCHER),
    SUBMITTED(Permission.SUBMIT_VOUCHER),
    RETURNED(Permission.REVIEW_VOUCHER),
    APPROVED(Permission.REVIEW_VOUCHER),
    POSTED(Permission.POST_VOUCHER),
    APPROVAL_WITHDRAWN(Permission.WITHDRAW_APPROVAL),
    CANCELLED(Permission.CANCEL_VOUCHER);

    private final Permission permission;

    VoucherAction(Permission permission) {
        this.permission = permission;
    }

    public Permission getPermission() {
        return permission;
    }
}
