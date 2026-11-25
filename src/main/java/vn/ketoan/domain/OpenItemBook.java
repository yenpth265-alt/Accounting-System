package vn.ketoan.domain;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import vn.ketoan.domain.value.Money;

/** Pure reconciliation rules and dated projections. No stored balance or repository dependency. */
public final class OpenItemBook {

    private final Map<JournalLineKey, OpenItem> originals = new LinkedHashMap<>();
    private final Map<JournalLineKey, NavigableMap<LocalDate, Money>> changes = new HashMap<>();

    public OpenItemBook(Collection<Account> accounts, Collection<JournalEntry> journal,
                        Collection<DebtAllocation> allocations, Collection<AllocationCancellation> cancellations) {
        Map<String, Account> accountIndex = accounts.stream()
                .collect(Collectors.toMap(Account::getCode, Function.identity()));
        Map<UUID, JournalEntry> entryIndex = journal.stream()
                .collect(Collectors.toMap(JournalEntry::getId, Function.identity()));
        Map<UUID, DebtAllocation> allocationIndex = allocations.stream()
                .collect(Collectors.toMap(DebtAllocation::id, Function.identity()));
        for (JournalEntry entry : journal) {
            for (JournalLine line : entry.getLines()) {
                Account account = Objects.requireNonNull(accountIndex.get(line.getAccountCode()), "Unknown journal account");
                if (account.getTracking() == AccountTracking.PARTNER
                        && (account.getType() == AccountType.ASSET || account.getType() == AccountType.LIABILITY)) {
                    boolean charge = account.getType() == AccountType.ASSET
                            ? line.getSide() == BalanceSide.DEBIT : line.getSide() == BalanceSide.CREDIT;
                    JournalLineKey key = new JournalLineKey(entry.getId(), line.getLineNumber());
                    originals.put(key, new OpenItem(key, entry.getSourceDocumentId(), entry.getContent().reference(),
                            entry.getPostingDate(), account.getCode(), line.getPartnerCode().orElseThrow(),
                            charge ? OpenItemKind.CHARGE : OpenItemKind.SETTLEMENT, line.getAmount(), Money.ZERO));
                }
            }
        }
        for (DebtAllocation allocation : allocations) {
            AllocationRequest request = allocation.request();
            OpenItem charge = requireItem(request.charge());
            OpenItem settlement = requireItem(request.settlement());
            if (charge.kind() != OpenItemKind.CHARGE || settlement.kind() != OpenItemKind.SETTLEMENT
                    || !charge.accountCode().equals(settlement.accountCode())
                    || !charge.partnerCode().equals(settlement.partnerCode())) {
                throw new IllegalArgumentException("Allocation must match opposite items of the same debt account and partner");
            }
            if (request.effectiveDate().isBefore(charge.postingDate())
                    || request.effectiveDate().isBefore(settlement.postingDate())
                    || allocation.recordedAt().isBefore(entryIndex.get(request.charge().journalEntryId()).getPostedAt())
                    || allocation.recordedAt().isBefore(entryIndex.get(request.settlement().journalEntryId()).getPostedAt())) {
                throw new IllegalArgumentException("Allocation cannot precede either source posting");
            }
            change(request.charge(), request.effectiveDate(), request.amount());
            change(request.settlement(), request.effectiveDate(), request.amount());
        }
        Map<UUID, AllocationCancellation> cancellationIndex = new HashMap<>();
        for (AllocationCancellation cancellation : cancellations) {
            if (cancellationIndex.putIfAbsent(cancellation.allocationId(), cancellation) != null) {
                throw new IllegalStateException("An allocation can only be cancelled once");
            }
            DebtAllocation allocation = allocationIndex.get(cancellation.allocationId());
            if (allocation == null) {
                throw new IllegalArgumentException("Cancellation requires an existing allocation");
            }
            AllocationRequest request = allocation.request();
            if (cancellation.effectiveDate().isBefore(request.effectiveDate())
                    || cancellation.recordedAt().isBefore(allocation.recordedAt())) {
                throw new IllegalArgumentException("Cancellation cannot precede the allocation");
            }
            change(request.charge(), cancellation.effectiveDate(), request.amount().negate());
            change(request.settlement(), cancellation.effectiveDate(), request.amount().negate());
        }
        // Check every effective date, including future dates, so backdated allocations cannot overspend capacity.
        for (Map.Entry<JournalLineKey, NavigableMap<LocalDate, Money>> entry : changes.entrySet()) {
            Money used = Money.ZERO;
            Money capacity = requireItem(entry.getKey()).original();
            for (Money delta : entry.getValue().values()) {
                used = used.add(delta);
                if (used.isNegative() || used.compareTo(capacity) > 0) {
                    throw new IllegalArgumentException("Allocation exceeds the debt or the available settlement amount");
                }
            }
        }
    }

    public static void requireDebtAccount(Account account) {
        Objects.requireNonNull(account, "Debt account is required");
        if (!account.isPostingAllowed() || account.getTracking() != AccountTracking.PARTNER
                || (account.getType() != AccountType.ASSET && account.getType() != AccountType.LIABILITY)) {
            throw new IllegalArgumentException("A debt account must be a partner-tracked posting asset or liability");
        }
    }

    public DebtStatement statement(String accountCode, String partnerCode, LocalDate asOf) {
        String account = Account.normalizeCode(accountCode);
        String partner = Partner.normalizeCode(partnerCode);
        DomainValidation.date(asOf, "Statement date");
        List<OpenItem> items = originals.values().stream()
                .filter(item -> item.accountCode().equals(account) && item.partnerCode().equals(partner))
                .filter(item -> !item.postingDate().isAfter(asOf))
                .sorted(Comparator.comparing(OpenItem::postingDate).thenComparing(OpenItem::reference)
                        .thenComparing(item -> item.key().lineNumber()).thenComparing(item -> item.key().journalEntryId()))
                .map(item -> new OpenItem(item.key(), item.sourceDocumentId(), item.reference(), item.postingDate(),
                        item.accountCode(), item.partnerCode(), item.kind(), item.original(), usedAt(item.key(), asOf)))
                .toList();
        return new DebtStatement(account, partner, asOf, items);
    }

    private OpenItem requireItem(JournalLineKey key) {
        OpenItem item = originals.get(key);
        if (item == null) {
            throw new IllegalArgumentException("Posted debt line not found: " + key);
        }
        return item;
    }

    private void change(JournalLineKey key, LocalDate date, Money amount) {
        changes.computeIfAbsent(key, ignored -> new TreeMap<>()).merge(date, amount, Money::add);
    }

    private Money usedAt(JournalLineKey key, LocalDate asOf) {
        NavigableMap<LocalDate, Money> timeline = changes.get(key);
        return timeline == null ? Money.ZERO
                : timeline.headMap(asOf, true).values().stream().reduce(Money.ZERO, Money::add);
    }
}
