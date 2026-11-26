package vn.ketoan.domain.report;

import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PeriodStatus;

/** One consistent, immutable read of catalogs, posted facts and closing metadata. */
public final class ReportContext {
    private final Map<String, Account> accounts;
    private final Map<String, Partner> partners;
    private final Map<String, MoneyAccount> funds;
    private final List<AccountingPeriod> periods;
    private final List<JournalRow> rows;
    private final List<JournalEntry> journal;
    private final ReportEvidence evidence;

    public ReportContext(Collection<Account> accounts, Collection<Partner> partners, Collection<MoneyAccount> funds,
                         Collection<AccountingPeriod> periods, Collection<JournalEntry> journal, Collection<ClosingBatch> closings) {
        this(accounts, partners, funds, periods, journal, closings, ReportEvidence.empty());
    }
    public ReportContext(Collection<Account> accounts, Collection<Partner> partners, Collection<MoneyAccount> funds,
                         Collection<AccountingPeriod> periods, Collection<JournalEntry> journal, Collection<ClosingBatch> closings, ReportEvidence evidence) {
        this.accounts = accounts.stream().collect(Collectors.toUnmodifiableMap(Account::getCode, Function.identity()));
        this.partners = partners.stream().collect(Collectors.toUnmodifiableMap(Partner::getCode, Function.identity()));
        this.funds = funds.stream().collect(Collectors.toUnmodifiableMap(MoneyAccount::getCode, Function.identity()));
        this.periods = List.copyOf(periods);
        this.journal = List.copyOf(journal);
        this.evidence = java.util.Objects.requireNonNull(evidence);
        Map<UUID, Integer> generated = new HashMap<>();
        for (ClosingBatch batch : closings) {
            for (int index = 0; index < batch.documentIds().size(); index++) {
                if (generated.putIfAbsent(batch.documentIds().get(index), index) != null) {
                    throw new IllegalArgumentException("Closing documents must belong to only one batch");
                }
            }
        }
        this.rows = journal.stream().flatMap(entry -> entry.getLines().stream().map(line -> {
                    requireAccount(line.getAccountCode());
                    return JournalRow.from(entry, line, generated.containsKey(entry.getSourceDocumentId()));
                })).sorted(Comparator.comparing(JournalRow::postingDate).thenComparing(JournalRow::postedAt)
                        .thenComparingInt(row -> generated.getOrDefault(row.documentId(), -1))
                        .thenComparing(JournalRow::reference).thenComparing(row -> row.entryId().toString()).thenComparingInt(JournalRow::lineNumber)).toList();
    }
    public List<JournalRow> rows() { return rows; }
    public List<JournalEntry> journal() { return journal; }
    public ReportEvidence evidence() { return evidence; }
    public List<Account> accounts() { return accounts.values().stream().sorted(Comparator.comparing(Account::getCode)).toList(); }
    public List<Partner> partners() { return partners.values().stream().sorted(Comparator.comparing(Partner::getCode)).toList(); }
    public List<MoneyAccount> funds() { return funds.values().stream().sorted(Comparator.comparing(MoneyAccount::getCode)).toList(); }
    public List<Account> postingAccounts() {
        return accounts.values().stream().filter(Account::isPostingAllowed).sorted(Comparator.comparing(Account::getCode)).toList();
    }
    public Account requireAccount(String code) {
        Account account = accounts.get(Account.normalizeCode(code));
        if (account == null) { throw new IllegalArgumentException("Report account not found: " + code); }
        return account;
    }
    public Set<String> accountFamily(String code) {
        requireAccount(code);
        var queue = new ArrayDeque<String>();
        queue.add(Account.normalizeCode(code));
        Set<String> visited = new HashSet<>();
        Set<String> posting = new HashSet<>();
        while (!queue.isEmpty()) {
            String current = queue.remove();
            if (!visited.add(current)) { throw new IllegalStateException("Account hierarchy contains a cycle"); }
            if (requireAccount(current).isPostingAllowed()) { posting.add(current); }
            accounts.values().stream().filter(account -> account.getParentCode().filter(current::equals).isPresent())
                    .map(Account::getCode).forEach(queue::add);
        }
        return Set.copyOf(posting);
    }
    public void requireFilters(LedgerQuery query, Set<String> family) {
        if (query.partnerCode() != null && !partners.containsKey(query.partnerCode())) { throw new IllegalArgumentException("Report partner not found"); }
        if (query.moneyAccountCode() != null) {
            MoneyAccount fund = funds.get(query.moneyAccountCode());
            if (fund == null || !family.contains(fund.getLedgerAccountCode())) {
                throw new IllegalArgumentException("Report money account does not belong to the selected ledger account");
            }
        }
    }
    public boolean periodsClosed(ReportPeriod range) {
        YearMonth end = YearMonth.from(range.to());
        YearMonth first = periods.stream().map(AccountingPeriod::getMonth).filter(month -> !month.isAfter(end))
                .min(Comparator.naturalOrder()).orElse(YearMonth.from(range.from()));
        if (YearMonth.from(range.from()).isBefore(first)) { first = YearMonth.from(range.from()); }
        long expected = ChronoUnit.MONTHS.between(first, end) + 1;
        return periods.stream().filter(period -> !period.getMonth().isAfter(end) && period.getStatus() == PeriodStatus.CLOSED).count() == expected;
    }
}
