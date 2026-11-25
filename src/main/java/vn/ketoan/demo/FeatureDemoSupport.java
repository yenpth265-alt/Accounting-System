package vn.ketoan.demo;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;
import java.util.UUID;

import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.application.service.AccountService;
import vn.ketoan.application.service.PostingService;
import vn.ketoan.application.service.VoucherValidationService;
import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.AccountType;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.BalanceSide;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLineKey;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.MoneyAccountKind;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.VoucherCreator;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherSnapshot;
import vn.ketoan.domain.VoucherStatus;

final class FeatureDemoSupport {

    static final LocalDate DATE = LocalDate.of(2026, 11, 25);
    static final VoucherValidatorFactory VALIDATORS = session -> new VoucherValidationService(
            session.accounts(), session.partners(), session.moneyAccounts(), session.periods());

    private FeatureDemoSupport() { }

    static UUID id(String key) { return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)); }

    static void seed(UnitOfWork transactions) {
        transactions.execute(session -> {
            AccountService accounts = new AccountService(session.accounts());
            for (Account account : new Account[]{account("1111", AccountType.ASSET, AccountTracking.MONEY_ACCOUNT),
                    account("1121", AccountType.ASSET, AccountTracking.MONEY_ACCOUNT), account("131", AccountType.ASSET, AccountTracking.PARTNER),
                    account("242", AccountType.ASSET, AccountTracking.NONE), account("4111", AccountType.EQUITY, AccountTracking.NONE),
                    account("5113", AccountType.REVENUE, AccountTracking.NONE), account("6427", AccountType.EXPENSE, AccountTracking.NONE)}) {
                if (session.accounts().findByCode(account.getCode()).isEmpty()) { accounts.add(account); }
            }
            if (session.moneyAccounts().findByCode("CASH01").isEmpty()) {
                session.moneyAccounts().insert(new MoneyAccount("CASH01", "Demo cash", "1111", MoneyAccountKind.CASH));
            }
            if (session.moneyAccounts().findByCode("BANK_PP").isEmpty()) {
                session.moneyAccounts().insert(new MoneyAccount("BANK_PP", "Prepaid demo bank", "1121", MoneyAccountKind.BANK));
            }
            if (session.partners().findByCode("KH_CORR").isEmpty()) {
                session.partners().insert(new Partner("KH_CORR", "Correction demo customer", Set.of(PartnerRole.CUSTOMER)));
            }
            for (int index = 0; index < 3; index++) {
                YearMonth month = YearMonth.from(DATE).plusMonths(index);
                if (session.periods().findByMonth(month).isEmpty()) { session.periods().insert(AccountingPeriod.open(month)); }
            }
            for (User user : new User[]{new User(1, "ketoan", "Accountant demo", Set.of(UserRole.ACCOUNTANT)),
                    new User(2, "truong", "Chief demo", Set.of(UserRole.CHIEF)), new User(4, "viewer", "Reader demo", Set.of(UserRole.VIEWER))}) {
                if (session.users().findById(user.getId()).isEmpty()) { session.users().insert(user); }
            }
            return null;
        });
    }

    static VoucherDocument ensure(UnitOfWork transactions, Clock clock, VoucherCreator creator, Draft input) {
        return transactions.execute(session -> {
            VoucherWorkflowService workflow = new VoucherWorkflowService(session.users(), session.documents(), VALIDATORS.create(session), clock);
            var existing = workflow.list(1).stream().filter(value -> value.getContent().getReference().equals(input.getReference())).findFirst();
            if (existing.isPresent()) {
                VoucherDocument value = existing.orElseThrow();
                if (value.getCreatedBy() != 1 || !VoucherSnapshot.from(value.getContent()).equals(VoucherSnapshot.from(creator.create(input)))) {
                    throw new IllegalStateException("Existing demo reference contains different content");
                }
                return value;
            }
            return workflow.create(creator, input, 1);
        });
    }

    static VoucherDocument approve(UnitOfWork transactions, Clock clock, VoucherDocument input) {
        return transactions.execute(session -> {
            VoucherWorkflowService workflow = new VoucherWorkflowService(session.users(), session.documents(), VALIDATORS.create(session), clock);
            VoucherDocument current = workflow.get(input.getId(), 1);
            if (current.getStatus() == VoucherStatus.DRAFT || current.getStatus() == VoucherStatus.RETURNED) {
                current = workflow.submit(current.getId(), current.getVersion(), 1);
            }
            if (current.getStatus() == VoucherStatus.PENDING_APPROVAL) { current = workflow.approve(current.getId(), current.getVersion(), 2); }
            return current;
        });
    }

    static JournalEntry post(UnitOfWork transactions, Clock clock, VoucherDocument input) {
        VoucherDocument approved = approve(transactions, clock, input);
        return new PostingService(transactions, VALIDATORS, clock).post(approved.getId(), approved.getVersion(), 2);
    }

    static JournalLineKey key(JournalEntry entry, String account) {
        return new JournalLineKey(entry.getId(), entry.getLines().stream().filter(line -> line.getAccountCode().equals(account))
                .findFirst().orElseThrow().getLineNumber());
    }

    private static Account account(String code, AccountType type, AccountTracking tracking) {
        BalanceSide balance = type == AccountType.ASSET || type == AccountType.EXPENSE ? BalanceSide.DEBIT : BalanceSide.CREDIT;
        return Account.posting(code, "Account " + code, type, balance, null, tracking);
    }
}
