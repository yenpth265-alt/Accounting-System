package vn.ketoan.domain;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAuthorizationTest {

    @ParameterizedTest
    @CsvSource({"ACCOUNTANT,CREATE_VOUCHER,true", "ACCOUNTANT,EDIT_VOUCHER,true",
            "ACCOUNTANT,REVIEW_VOUCHER,false", "CHIEF,REVIEW_VOUCHER,true",
            "CHIEF,CREATE_VOUCHER,false", "VIEWER,VIEW_VOUCHERS,true",
            "VIEWER,SUBMIT_VOUCHER,false", "ADMIN,VIEW_VOUCHERS,false",
            "ADMIN,REVIEW_VOUCHER,false", "CHIEF,WITHDRAW_APPROVAL,true",
            "CHIEF,ALLOCATE_DEBT,true", "CHIEF,CANCEL_DEBT_ALLOCATION,true",
            "ACCOUNTANT,ALLOCATE_DEBT,false", "ACCOUNTANT,CANCEL_DEBT_ALLOCATION,false"})
    void rolesGrantOnlyTheirDeclaredAccountingPermissions(UserRole role, Permission permission, boolean allowed) {
        User user = new User(1, "User1", "Name", Set.of(role));
        if (allowed) {
            user.requirePermission(permission);
        } else {
            assertThrows(AccessDeniedException.class, () -> user.requirePermission(permission));
        }
    }

    @ParameterizedTest
    @EnumSource(Permission.class)
    void inactiveUsersLoseAllAccountingPermissions(Permission permission) {
        User user = new User(1, "owner", "Owner", Set.of(UserRole.ACCOUNTANT, UserRole.CHIEF)).deactivate();
        assertThrows(AccessDeniedException.class, () -> user.requirePermission(permission));
    }

    @Test
    void rolesAreDefensivelyCopiedAndCanBeCombinedExplicitly() {
        Set<UserRole> roles = EnumSet.of(UserRole.ACCOUNTANT);
        User original = new User(1, " USER1 ", " Owner ", roles);
        roles.add(UserRole.CHIEF);

        assertEquals("user1", original.getUsername());
        assertEquals("Owner", original.getDisplayName());
        assertFalse(original.hasRole(UserRole.CHIEF));
        assertThrows(UnsupportedOperationException.class, () -> original.getRoles().add(UserRole.CHIEF));
        User combined = new User(2, "combined", "Combined", Set.of(UserRole.ACCOUNTANT, UserRole.CHIEF));
        combined.requirePermission(Permission.CREATE_VOUCHER);
        combined.requirePermission(Permission.REVIEW_VOUCHER);
        assertTrue(original.isActive());
        assertFalse(original.deactivate().isActive());
        original.deactivate().activate().requirePermission(Permission.CREATE_VOUCHER);
    }

    @Test
    void invalidUserDataIsRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new User(0, "user", "Name", Set.of(UserRole.ACCOUNTANT))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new User(1, " ", "Name", Set.of(UserRole.ACCOUNTANT))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new User(1, "user", "a".repeat(81), Set.of(UserRole.ACCOUNTANT))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new User(1, "user", "Name", Set.of())),
                () -> assertThrows(NullPointerException.class, () -> new User(1, "user", "Name", null)));
    }
}
