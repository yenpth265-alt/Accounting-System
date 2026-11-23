package vn.ketoan.infrastructure.memory;

import java.time.YearMonth;
import java.util.Set;

import org.junit.jupiter.api.Test;

import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryRepositoryTest {

    @Test
    void updateCannotSilentlyInsertMissingMasterData() {
        InMemoryAccountRepository accounts = new InMemoryAccountRepository();
        InMemoryPartnerRepository partners = new InMemoryPartnerRepository();
        InMemoryAccountingPeriodRepository periods = new InMemoryAccountingPeriodRepository();

        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> accounts.update(Account.posting("131", "Receivables", AccountType.ASSET,
                                BalanceSide.DEBIT, null, AccountTracking.PARTNER))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> partners.update(new Partner("KH01", "Customer A", Set.of(PartnerRole.CUSTOMER)))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> periods.update(AccountingPeriod.open(YearMonth.of(2026, 11)))),
                () -> assertTrue(accounts.findAll().isEmpty()),
                () -> assertTrue(partners.findAll().isEmpty()),
                () -> assertTrue(periods.findAll().isEmpty()));
    }
}
