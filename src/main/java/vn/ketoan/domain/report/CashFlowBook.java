package vn.ketoan.domain.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountTracking;
import vn.ketoan.domain.CorrectionKind;
import vn.ketoan.domain.CorrectionRecord;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.value.Money;

/** Reconciles external cash, internal transfers and source-inherited reversal classifications. */
public final class CashFlowBook {
    private final Map<String, Account> accounts;
    private final Map<UUID, JournalEntry> journal;
    private final Map<UUID, JournalEntry> byDocument;
    private final Map<UUID, UUID> reversalSources = new HashMap<>();
    private final Map<UUID, CashFlowClassification> latest = new HashMap<>();

    public CashFlowBook(Collection<Account> accounts, Collection<JournalEntry> journal,
                        Collection<CorrectionRecord> corrections, Collection<CashFlowClassification> classifications) {
        this.accounts = accounts.stream().collect(Collectors.toMap(Account::getCode, Function.identity()));
        this.journal = journal.stream().collect(Collectors.toMap(JournalEntry::getId, Function.identity()));
        this.byDocument = journal.stream().collect(Collectors.toMap(JournalEntry::getSourceDocumentId, Function.identity()));
        corrections.stream().filter(record -> record.kind() == CorrectionKind.REVERSAL)
                .forEach(record -> reversalSources.put(record.targetDocumentId(), record.sourceDocumentId()));
        var ids = new HashSet<UUID>();
        for (CashFlowClassification value : classifications.stream().sorted(Comparator.comparingInt(CashFlowClassification::revision)).toList()) {
            JournalEntry entry = entry(value.entryId());
            CashFlowClassification previous = latest.get(value.entryId());
            int expected = previous == null ? 1 : Math.incrementExact(previous.revision());
            if (!ids.add(value.id()) || value.revision() != expected || value.recordedAt().isBefore(entry.getPostedAt())
                    || (previous != null && value.recordedAt().isBefore(previous.recordedAt()))) {
                throw new IllegalArgumentException("Cash flow classification history is inconsistent");
            }
            if (reversalSources.containsKey(entry.getSourceDocumentId())) {
                throw new IllegalStateException("A generated reversal inherits classification from its source; classify that source instead");
            }
            validate(entry, value.internalTransfer(), value.splits());
            latest.put(entry.getId(), value);
        }
    }
    public CashFlowClassification latest(UUID entryId) { return latest.get(entryId); }
    public Totals totals(JournalEntry entry) {
        DebitCredit cash = entry.getLines().stream().filter(line -> account(line.getAccountCode()).getTracking() == AccountTracking.MONEY_ACCOUNT)
                .map(line -> new DebitCredit(line.getDebit(), line.getCredit())).reduce(DebitCredit.ZERO, DebitCredit::add);
        boolean pure = !cash.equals(DebitCredit.ZERO) && entry.getLines().stream().allMatch(line -> account(line.getAccountCode()).getTracking() == AccountTracking.MONEY_ACCOUNT);
        return new Totals(cash, pure);
    }
    public Breakdown breakdown(UUID id) { return breakdown(entry(id), new HashSet<>()); }
    private Breakdown breakdown(JournalEntry entry, Set<UUID> path) {
        if (!path.add(entry.getId())) { throw new IllegalArgumentException("Cash flow reversal links contain a cycle"); }
        Totals totals = totals(entry);
        if (totals.amounts().equals(DebitCredit.ZERO)) { throw new IllegalArgumentException("Journal entry has no cash movements"); }
        UUID sourceId = reversalSources.get(entry.getSourceDocumentId());
        if (sourceId != null) {
            JournalEntry source = byDocument.get(sourceId);
            if (source == null) { throw new IllegalArgumentException("Cash flow reversal source not found"); }
            Breakdown original = breakdown(source, path);
            return new Breakdown(original.internalTransfer(), original.splits().stream()
                    .map(split -> new CashFlowSplit(split.activity(), split.direction().opposite(), split.amount())).toList(), original.basis(), true);
        }
        CashFlowClassification stored = latest.get(entry.getId());
        if (stored != null) { return new Breakdown(stored.internalTransfer(), stored.splits(), stored, false); }
        if (totals.pureTransfer()) { return new Breakdown(totals.amounts().debit(), List.of(), null, false); }
        var splits = new ArrayList<CashFlowSplit>();
        if (totals.amounts().debit().isPositive()) { splits.add(new CashFlowSplit(CashFlowActivity.UNCLASSIFIED, CashFlowDirection.INFLOW, totals.amounts().debit())); }
        if (totals.amounts().credit().isPositive()) { splits.add(new CashFlowSplit(CashFlowActivity.UNCLASSIFIED, CashFlowDirection.OUTFLOW, totals.amounts().credit())); }
        return new Breakdown(Money.ZERO, splits, null, false);
    }
    private void validate(JournalEntry entry, Money internal, List<CashFlowSplit> splits) {
        Totals totals = totals(entry);
        if (totals.amounts().equals(DebitCredit.ZERO) || internal.compareTo(totals.amounts().debit()) > 0 || internal.compareTo(totals.amounts().credit()) > 0
                || (totals.pureTransfer() && (!internal.equals(totals.amounts().debit()) || !splits.isEmpty()))) {
            throw new IllegalArgumentException("Internal transfer does not match this entry's cash movements");
        }
        Money incoming = sum(splits, CashFlowDirection.INFLOW);
        Money outgoing = sum(splits, CashFlowDirection.OUTFLOW);
        if (!incoming.add(internal).equals(totals.amounts().debit()) || !outgoing.add(internal).equals(totals.amounts().credit())) {
            throw new IllegalArgumentException("Classified external cash plus internal transfers must reconcile to every cash debit and credit");
        }
    }
    private Money sum(List<CashFlowSplit> splits, CashFlowDirection direction) { return splits.stream().filter(split -> split.direction() == direction).map(CashFlowSplit::amount).reduce(Money.ZERO, Money::add); }
    private JournalEntry entry(UUID id) {
        JournalEntry value = journal.get(id);
        if (value == null) { throw new IllegalArgumentException("Posted cash flow journal entry not found"); }
        return value;
    }
    private Account account(String code) {
        Account value = accounts.get(code);
        if (value == null) { throw new IllegalArgumentException("Cash flow account not found"); }
        return value;
    }
    public record Totals(DebitCredit amounts, boolean pureTransfer) { }
    public record Breakdown(Money internalTransfer, List<CashFlowSplit> splits, CashFlowClassification basis, boolean reversal) {
        public Breakdown { splits = List.copyOf(splits); }
    }
}
