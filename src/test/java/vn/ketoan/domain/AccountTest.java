package vn.ketoan.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AccountTest {

    @Test
    void groupAccountCannotReceivePostings() {
        Account account = Account.group("111", "Cash", AccountType.ASSET, BalanceSide.DEBIT, null);

        assertAll(
                () -> assertFalse(account.isPostingAllowed()),
                () -> assertEquals(AccountTracking.NONE, account.getTracking()),
                () -> assertThrows(IllegalStateException.class, account::requirePostingAllowed));
    }

    @Test
    void postingAccountCarriesItsRequiredDetailAndParent() {
        Account account = cashAccount();

        account.requirePostingAllowed();
        assertAll(
                () -> assertEquals("111", account.getParentCode().orElseThrow()),
                () -> assertEquals(AccountTracking.MONEY_ACCOUNT, account.getTracking()),
                () -> assertTrue(account.isActive()));
    }

    @Test
    void normalizesCodesWithoutChangingNames() {
        Account account = Account.posting(" tm_vnd ", " Tien mat ", AccountType.ASSET,
                BalanceSide.DEBIT, " tm ", AccountTracking.MONEY_ACCOUNT);

        assertAll(
                () -> assertEquals("TM_VND", account.getCode()),
                () -> assertEquals("TM", account.getParentCode().orElseThrow()),
                () -> assertEquals("Tien mat", account.getName()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "111 1", "<111>", "ß", "123456789012345678901"})
    void rejectsInvalidAccountCodes(String code) {
        assertThrows(IllegalArgumentException.class,
                () -> Account.group(code, "Cash", AccountType.ASSET, BalanceSide.DEBIT, null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t\n"})
    void rejectsBlankNames(String name) {
        assertThrows(IllegalArgumentException.class, () -> cashAccount().rename(name));
    }

    @Test
    void rejectsNamesLongerThanTheStorageLimit() {
        assertThrows(IllegalArgumentException.class, () -> cashAccount().rename("a".repeat(121)));
    }

    @Test
    void rejectsItsOwnCodeAsParentAfterNormalization() {
        assertThrows(IllegalArgumentException.class,
                () -> Account.group("tm", "Cash", AccountType.ASSET, BalanceSide.DEBIT, " TM "));
    }

    @Test
    void deactivationAndRenameProduceNewDefinitions() {
        Account original = cashAccount();
        Account updated = original.deactivate().rename("Renamed cash");

        assertAll(
                () -> assertTrue(original.isActive()),
                () -> assertEquals("Cash in VND", original.getName()),
                () -> assertFalse(updated.isActive()),
                () -> assertEquals("Renamed cash", updated.getName()),
                () -> assertEquals(original.getCode(), updated.getCode()),
                () -> assertEquals(original.getParentCode(), updated.getParentCode()),
                () -> assertThrows(IllegalStateException.class, updated::requirePostingAllowed));
        updated.activate().requirePostingAllowed();
    }

    @Test
    void allowsAnAssetAccountWithCreditNormalBalance() {
        Account account = Account.posting("214", "Accumulated depreciation", AccountType.ASSET,
                BalanceSide.CREDIT, null, AccountTracking.NONE);

        assertEquals(BalanceSide.CREDIT, account.getNormalBalance());
    }

    @Test
    void rejectsMissingClassification() {
        assertAll(
                () -> assertThrows(NullPointerException.class,
                        () -> Account.group("111", "Cash", null, BalanceSide.DEBIT, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> Account.group("111", "Cash", AccountType.ASSET, null, null)),
                () -> assertThrows(NullPointerException.class,
                        () -> Account.posting("1111", "Cash", AccountType.ASSET,
                                BalanceSide.DEBIT, "111", null)));
    }

    private Account cashAccount() {
        return Account.posting("1111", "Cash in VND", AccountType.ASSET,
                BalanceSide.DEBIT, "111", AccountTracking.MONEY_ACCOUNT);
    }
}
