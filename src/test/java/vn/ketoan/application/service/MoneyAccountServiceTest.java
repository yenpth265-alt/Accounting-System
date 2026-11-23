package vn.ketoan.application.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import vn.ketoan.VoucherTestData.Stores;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.MoneyAccountKind;
import vn.ketoan.infrastructure.memory.InMemoryMoneyAccountRepository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static vn.ketoan.VoucherTestData.stores;

class MoneyAccountServiceTest {

    @Test
    void createsAFundOnlyForAnActiveMoneyTrackedAssetPostingAccount() {
        Stores stores = stores();
        MoneyAccountService service = new MoneyAccountService(stores.moneyAccounts(), stores.accounts());
        service.add(new MoneyAccount(" cash03 ", " New fund ", "1111", MoneyAccountKind.CASH));

        assertEquals("New fund", service.get("CASH03").getName());
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.add(new MoneyAccount("BAD1", "Bad", "999", MoneyAccountKind.CASH))),
                () -> assertThrows(IllegalStateException.class,
                        () -> service.add(new MoneyAccount("BAD2", "Bad", "111", MoneyAccountKind.CASH))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> service.add(new MoneyAccount("BAD3", "Bad", "5113", MoneyAccountKind.BANK))),
                () -> assertThrows(IllegalStateException.class,
                        () -> service.add(new MoneyAccount(" cash03 ", "Replacement", "1111", MoneyAccountKind.CASH))));
        assertEquals("New fund", service.get("cash03").getName());
    }

    @Test
    void rejectsWrongLedgerBindingAndInactiveDefinitions() {
        Stores stores = stores();
        MoneyAccount original = stores.moneyAccounts().findByCode("CASH01").orElseThrow();
        MoneyAccount inactive = original.deactivate();

        assertTrue(original.isActive());
        assertFalse(inactive.isActive());
        assertThrows(IllegalArgumentException.class,
                () -> original.requireUsableWith(stores.accounts().findByCode("1121").orElseThrow()));
        assertThrows(IllegalStateException.class,
                () -> inactive.requireUsableWith(stores.accounts().findByCode("1111").orElseThrow()));
    }

    @Test
    void reactivationRequiresTheLinkedLedgerAccountToRemainActive() {
        Stores stores = stores();
        MoneyAccountService service = new MoneyAccountService(stores.moneyAccounts(), stores.accounts());
        service.deactivate("CASH01");
        Account account = stores.accounts().findByCode("1111").orElseThrow();
        stores.accounts().update(account.deactivate());

        assertThrows(IllegalStateException.class, () -> service.activate("CASH01"));
        assertFalse(service.get("CASH01").isActive());
        stores.accounts().update(account);
        service.activate("CASH01");
        assertTrue(service.get("CASH01").isActive());
    }

    @Test
    void validatesCodesNamesAndKinds() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MoneyAccount(" ", "Name", "1111", MoneyAccountKind.CASH)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MoneyAccount("a".repeat(33), "Name", "1111", MoneyAccountKind.CASH)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new MoneyAccount("CASH01", "a".repeat(121), "1111", MoneyAccountKind.CASH)),
                () -> assertThrows(NullPointerException.class,
                        () -> new MoneyAccount("CASH01", "Name", "1111", null)));
    }

    @Test
    void storageReturnsImmutableSnapshotsAndDoesNotUpsert() {
        InMemoryMoneyAccountRepository repository = new InMemoryMoneyAccountRepository();
        MoneyAccount fund = new MoneyAccount("CASH01", "Cash", "1111", MoneyAccountKind.CASH);
        assertThrows(IllegalArgumentException.class, () -> repository.update(fund));
        assertTrue(repository.findAll().isEmpty());
        repository.insert(fund);
        List<MoneyAccount> snapshot = repository.findAll();
        repository.update(fund.deactivate());

        assertTrue(snapshot.getFirst().isActive());
        assertFalse(repository.findByCode("CASH01").orElseThrow().isActive());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }
}
