package vn.ketoan.domain;

import java.util.EnumSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartnerTest {

    @Test
    void supportsBothCustomerAndSupplierRoles() {
        Partner customer = customer();
        Partner both = customer.addRole(PartnerRole.SUPPLIER);

        both.requireRole(PartnerRole.CUSTOMER);
        both.requireRole(PartnerRole.SUPPLIER);
        assertAll(
                () -> assertEquals(Set.of(PartnerRole.CUSTOMER), customer.getRoles()),
                () -> assertEquals(Set.of(PartnerRole.CUSTOMER, PartnerRole.SUPPLIER), both.getRoles()));
    }

    @Test
    void roleMembershipCannotBeChangedThroughInputOrGetter() {
        Set<PartnerRole> suppliedRoles = EnumSet.of(PartnerRole.CUSTOMER);
        Partner partner = new Partner("KH01", "Customer A", suppliedRoles);
        suppliedRoles.add(PartnerRole.SUPPLIER);

        assertEquals(Set.of(PartnerRole.CUSTOMER), partner.getRoles());
        assertThrows(UnsupportedOperationException.class,
                () -> partner.getRoles().add(PartnerRole.SUPPLIER));
    }

    @Test
    void requiresAtLeastOneRole() {
        assertThrows(IllegalArgumentException.class,
                () -> new Partner("KH01", "Customer A", Set.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "KH 01", "<KH01>", "ß"})
    void rejectsInvalidPartnerCodes(String code) {
        assertThrows(IllegalArgumentException.class,
                () -> new Partner(code, "Customer A", Set.of(PartnerRole.CUSTOMER)));
    }

    @Test
    void appliesStorageLengthsAndCanonicalCodes() {
        Partner partner = new Partner(" kh01 ", " Customer A ", Set.of(PartnerRole.CUSTOMER));

        assertAll(
                () -> assertEquals("KH01", partner.getCode()),
                () -> assertEquals("Customer A", partner.getName()),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Partner("a".repeat(33), "Name", Set.of(PartnerRole.CUSTOMER))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> partner.rename("a".repeat(161))));
    }

    @Test
    void inactivePartnerCannotBeUsedInNewBusinessOperations() {
        Partner original = customer();
        Partner inactive = original.deactivate();

        assertAll(
                () -> assertTrue(original.isActive()),
                () -> assertFalse(inactive.isActive()),
                () -> assertThrows(IllegalStateException.class,
                        () -> inactive.requireRole(PartnerRole.CUSTOMER)));
        inactive.activate().requireRole(PartnerRole.CUSTOMER);
    }

    @Test
    void aCustomerIsNotAutomaticallyASupplier() {
        assertThrows(IllegalStateException.class,
                () -> customer().requireRole(PartnerRole.SUPPLIER));
    }

    @Test
    void rejectsMissingOrBlankPartnerData() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> customer().rename(" ")),
                () -> assertThrows(NullPointerException.class,
                        () -> new Partner("KH01", "Name", null)),
                () -> assertThrows(NullPointerException.class, () -> customer().addRole(null)),
                () -> assertThrows(NullPointerException.class, () -> customer().requireRole(null)));
    }

    private Partner customer() {
        return new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER));
    }
}
