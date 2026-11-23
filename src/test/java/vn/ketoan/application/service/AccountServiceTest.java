package vn.ketoan.application.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.infrastructure.memory.InMemoryAccountRepository;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountServiceTest {

    private AccountService accounts;

    @BeforeEach
    void setUp() {
        accounts = new AccountService(new InMemoryAccountRepository());
    }

    @Test
    void storesValidHierarchyAndChecksPostingEligibility() {
        addCashHierarchy();

        assertEquals("1111", accounts.requirePostingAccount(" 1111 ").getCode());
        assertThrows(IllegalStateException.class, () -> accounts.requirePostingAccount("111"));
    }

    @Test
    void rejectsMissingParentWithoutSavingTheChild() {
        assertThrows(IllegalArgumentException.class, () -> accounts.add(cashChild()));
        assertTrue(accounts.list().isEmpty());
    }

    @Test
    void postingAccountCannotBecomeAParent() {
        accounts.add(Account.posting("111", "Cash", AccountType.ASSET,
                BalanceSide.DEBIT, null, AccountTracking.MONEY_ACCOUNT));

        assertThrows(IllegalStateException.class, () -> accounts.add(cashChild()));
        assertEquals(1, accounts.list().size());
    }

    @Test
    void rejectsChildWithDifferentFinancialType() {
        accounts.add(cashParent());

        assertThrows(IllegalArgumentException.class,
                () -> accounts.add(Account.posting("1111", "Wrong revenue", AccountType.REVENUE,
                        BalanceSide.CREDIT, "111", AccountTracking.NONE)));
    }

    @Test
    void parentAndChildMayHaveDifferentNormalBalanceSides() {
        accounts.add(cashParent());
        accounts.add(Account.posting("1119", "Credit-normal detail", AccountType.ASSET,
                BalanceSide.CREDIT, "111", AccountTracking.NONE));

        assertEquals(BalanceSide.CREDIT, accounts.get("1119").getNormalBalance());
    }

    @Test
    void duplicateCanonicalCodeDoesNotOverwriteExistingAccount() {
        accounts.add(Account.group("tm", "Original", AccountType.ASSET, BalanceSide.DEBIT, null));

        assertThrows(IllegalStateException.class,
                () -> accounts.add(Account.group(" TM ", "Replacement", AccountType.ASSET,
                        BalanceSide.DEBIT, null)));
        assertEquals("Original", accounts.get("tm").getName());
    }

    @Test
    void cannotDeactivateParentWhileItsChildrenRemainActive() {
        addCashHierarchy();

        assertThrows(IllegalStateException.class, () -> accounts.deactivate("111"));
        assertAll(
                () -> assertTrue(accounts.get("111").isActive()),
                () -> assertTrue(accounts.get("1111").isActive()));
    }

    @Test
    void reactivationRequiresAnActiveParent() {
        addCashHierarchy();
        accounts.deactivate("1111");
        accounts.deactivate("111");

        assertThrows(IllegalStateException.class, () -> accounts.activate("1111"));
        assertFalse(accounts.get("1111").isActive());

        accounts.activate("111");
        accounts.activate("1111");
        accounts.requirePostingAccount("1111");
    }

    @Test
    void cannotAddAChildUnderAnInactiveParent() {
        accounts.add(cashParent());
        accounts.deactivate("111");

        assertThrows(IllegalStateException.class, () -> accounts.add(cashChild()));
    }

    @Test
    void immutableReadsCannotSilentlyChangeStoredData() {
        addCashHierarchy();
        Account original = accounts.get("1111");
        original.deactivate();
        List<Account> snapshot = accounts.list();
        accounts.rename("1111", "Updated cash");

        assertAll(
                () -> assertTrue(accounts.get("1111").isActive()),
                () -> assertEquals("Cash in VND", original.getName()),
                () -> assertEquals("Cash in VND", snapshot.get(1).getName()),
                () -> assertEquals("Updated cash", accounts.get("1111").getName()),
                () -> assertThrows(UnsupportedOperationException.class, snapshot::clear));
    }

    @Test
    void accountsAreListedInCodeOrder() {
        accounts.add(Account.posting("331", "Payables", AccountType.LIABILITY,
                BalanceSide.CREDIT, null, AccountTracking.PARTNER));
        addCashHierarchy();

        assertEquals(List.of("111", "1111", "331"),
                accounts.list().stream().map(Account::getCode).toList());
    }

    @Test
    void missingAccountCannotBeUpdatedOrUsedForPosting() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> accounts.get("999")),
                () -> assertThrows(IllegalArgumentException.class, () -> accounts.rename("999", "Name")),
                () -> assertThrows(IllegalArgumentException.class, () -> accounts.deactivate("999")),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> accounts.requirePostingAccount("999")));
        assertTrue(accounts.list().isEmpty());
    }

    private void addCashHierarchy() {
        accounts.add(cashParent());
        accounts.add(cashChild());
    }

    private Account cashParent() {
        return Account.group("111", "Cash", AccountType.ASSET, BalanceSide.DEBIT, null);
    }

    private Account cashChild() {
        return Account.posting("1111", "Cash in VND", AccountType.ASSET,
                BalanceSide.DEBIT, "111", AccountTracking.MONEY_ACCOUNT);
    }
}
