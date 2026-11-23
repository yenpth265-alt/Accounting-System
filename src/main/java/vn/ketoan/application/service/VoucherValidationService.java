package vn.ketoan.application.service;

import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import vn.ketoan.application.port.AccountRepository;
import vn.ketoan.application.port.AccountingPeriodRepository;
import vn.ketoan.application.port.MoneyAccountRepository;
import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.Voucher;
import vn.ketoan.domain.VoucherLine;
import vn.ketoan.domain.VoucherValidationContext;

/** Resolves current master data on each check, then delegates all voucher rules to the domain. */
public final class VoucherValidationService implements VoucherValidator {

    private final AccountRepository accounts;
    private final PartnerRepository partners;
    private final MoneyAccountRepository moneyAccounts;
    private final AccountingPeriodRepository periods;

    public VoucherValidationService(AccountRepository accounts, PartnerRepository partners,
                                     MoneyAccountRepository moneyAccounts, AccountingPeriodRepository periods) {
        this.accounts = Objects.requireNonNull(accounts, "Account repository is required");
        this.partners = Objects.requireNonNull(partners, "Partner repository is required");
        this.moneyAccounts = Objects.requireNonNull(moneyAccounts, "Money account repository is required");
        this.periods = Objects.requireNonNull(periods, "Period repository is required");
    }

    @Override
    public void validate(Voucher voucher) {
        Objects.requireNonNull(voucher, "Voucher is required");
        YearMonth month = YearMonth.from(voucher.getPostingDate());
        AccountingPeriod period = periods.findByMonth(month).orElseThrow(
                () -> new IllegalArgumentException("Accounting period not found: " + month));
        Map<String, Account> resolvedAccounts = new LinkedHashMap<>();
        Map<String, Partner> resolvedPartners = new LinkedHashMap<>();
        Map<String, MoneyAccount> resolvedMoneyAccounts = new LinkedHashMap<>();
        for (VoucherLine line : voucher.getLines()) {
            resolvedAccounts.computeIfAbsent(line.getAccountCode(), code -> accounts.findByCode(code).orElseThrow(
                    () -> new IllegalArgumentException("Account not found: " + code)));
            line.getPartnerCode().ifPresent(code -> resolvedPartners.computeIfAbsent(code,
                    key -> partners.findByCode(key).orElseThrow(
                            () -> new IllegalArgumentException("Partner not found: " + key))));
            line.getMoneyAccountCode().ifPresent(code -> resolvedMoneyAccounts.computeIfAbsent(code,
                    key -> moneyAccounts.findByCode(key).orElseThrow(
                            () -> new IllegalArgumentException("Money account not found: " + key))));
        }
        VoucherValidationContext context = new VoucherValidationContext(resolvedAccounts.values(),
                resolvedPartners.values(), resolvedMoneyAccounts.values(), period);
        voucher.validateForSubmission(context);
    }
}
