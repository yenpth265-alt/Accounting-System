package vn.ketoan.application.service;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.infrastructure.memory.InMemoryPartnerRepository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PartnerServiceTest {

    private PartnerService partners;

    @BeforeEach
    void setUp() {
        partners = new PartnerService(new InMemoryPartnerRepository());
    }

    @Test
    void customerCanAcquireSupplierRoleWithoutAnotherIdentity() {
        partners.add(customer());
        partners.addRole(" kh01 ", PartnerRole.SUPPLIER);

        partners.requireRole("KH01", PartnerRole.CUSTOMER);
        partners.requireRole("kh01", PartnerRole.SUPPLIER);
        assertEquals(1, partners.list().size());
    }

    @Test
    void duplicateCanonicalCodeDoesNotReplaceExistingPartner() {
        partners.add(customer());

        assertThrows(IllegalStateException.class,
                () -> partners.add(new Partner(" kh01 ", "Replacement", Set.of(PartnerRole.SUPPLIER))));
        assertEquals("Customer A", partners.get("KH01").getName());
    }

    @Test
    void deactivationRetainsThePartnerButPreventsNewUse() {
        partners.add(customer());
        partners.deactivate("KH01");

        assertAll(
                () -> assertEquals(1, partners.list().size()),
                () -> assertFalse(partners.get("KH01").isActive()),
                () -> assertThrows(IllegalStateException.class,
                        () -> partners.requireRole("KH01", PartnerRole.CUSTOMER)));
        partners.activate("KH01");
        partners.requireRole("KH01", PartnerRole.CUSTOMER);
    }

    @Test
    void wrongRoleIsRejected() {
        partners.add(customer());

        assertThrows(IllegalStateException.class,
                () -> partners.requireRole("KH01", PartnerRole.SUPPLIER));
    }

    @Test
    void renamePersistsANewDefinitionAndPreservesEarlierSnapshot() {
        partners.add(customer());
        List<Partner> snapshot = partners.list();
        partners.rename("KH01", " Renamed customer ");

        assertAll(
                () -> assertEquals("Customer A", snapshot.getFirst().getName()),
                () -> assertEquals("Renamed customer", partners.get("KH01").getName()),
                () -> assertThrows(UnsupportedOperationException.class, snapshot::clear));
    }

    @Test
    void missingPartnerCannotBeCreatedByAnUpdate() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> partners.rename("KH01", "Name")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> partners.addRole("KH01", PartnerRole.CUSTOMER)),
                () -> assertThrows(IllegalArgumentException.class, () -> partners.deactivate("KH01")));
        assertEquals(0, partners.list().size());
    }

    private Partner customer() {
        return new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER));
    }
}
