package vn.ketoan.domain;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Source links, generated reversal content and mandatory matching, independent of storage. */
public final class CorrectionBook {

    private final Map<UUID, VoucherDocument> documents;
    private final Map<UUID, JournalEntry> journalBySource;
    private final Map<String, Account> accounts;
    private final Map<UUID, CorrectionRecord> byTarget;
    private final Map<UUID, CorrectionRecord> reversals = new HashMap<>();

    public CorrectionBook(Collection<Account> accounts, Collection<VoucherDocument> documents,
                          Collection<JournalEntry> journal, Collection<CorrectionRecord> records) {
        this.accounts = accounts.stream().collect(Collectors.toMap(Account::getCode, Function.identity()));
        this.documents = documents.stream().collect(Collectors.toMap(VoucherDocument::getId, Function.identity()));
        this.journalBySource = journal.stream().collect(Collectors.toMap(JournalEntry::getSourceDocumentId, Function.identity()));
        this.byTarget = records.stream().collect(Collectors.toMap(CorrectionRecord::targetDocumentId, Function.identity()));
        for (CorrectionRecord record : records) {
            VoucherDocument source = document(record.sourceDocumentId());
            VoucherDocument target = document(record.targetDocumentId());
            JournalEntry original = journalBySource.get(source.getId());
            if (source.getStatus() != VoucherStatus.POSTED || original == null || target.getContent().getType() != VoucherType.GENERAL
                    || target.getCreatedBy() != record.createdBy() || !target.getCreatedAt().equals(record.createdAt())
                    || record.createdAt().isBefore(original.getPostedAt())
                    || target.getContent().getPostingDate().isBefore(original.getPostingDate())) {
                throw new IllegalArgumentException("A correction requires a posted source, matching owner/time and a valid posting date");
            }
            Set<UUID> path = new HashSet<>();
            UUID current = target.getId();
            while (byTarget.containsKey(current)) {
                if (!path.add(current)) { throw new IllegalArgumentException("Correction source links cannot form a cycle"); }
                current = byTarget.get(current).sourceDocumentId();
            }
            if (record.kind() == CorrectionKind.REVERSAL) {
                CorrectionRecord parent = byTarget.get(source.getId());
                if (parent != null && parent.kind() == CorrectionKind.REVERSAL) {
                    throw new IllegalArgumentException("Use an adjustment or a new voucher to correct a reversal");
                }
                ReversalRequest request = new ReversalRequest(record.id(), source.getId(), target.getContent().getReference(),
                        target.getHistory().getFirst().content().postingDate(), record.reason());
                VoucherSnapshot expected = VoucherSnapshot.from(new GeneralCreator().create(request.toDraft(original)));
                if (!expected.equals(VoucherSnapshot.from(target.getContent()))) {
                    throw new IllegalArgumentException("Reversal content is generated and cannot be edited; cancel it and create a new request");
                }
                if (target.getStatus() != VoucherStatus.CANCELLED && reversals.putIfAbsent(source.getId(), record) != null) {
                    throw new IllegalStateException("A source can have only one live reversal");
                }
            }
        }
        for (CorrectionRecord reversal : reversals.values()) {
            for (CorrectionRecord record : records) {
                if (record.kind() == CorrectionKind.ADJUSTMENT && record.sourceDocumentId().equals(reversal.sourceDocumentId())) {
                    VoucherDocument child = document(record.targetDocumentId());
                    if (child.getStatus() != VoucherStatus.CANCELLED && postedReversal(child.getId()).isEmpty()) {
                        throw new IllegalStateException("Cancel pending adjustments or reverse posted adjustments before reversing their source");
                    }
                }
            }
        }
    }

    public Optional<CorrectionRecord> activeReversal(UUID sourceDocumentId) {
        return Optional.ofNullable(reversals.get(sourceDocumentId));
    }

    public Optional<CorrectionRecord> correctionOf(UUID targetDocumentId) {
        return Optional.ofNullable(byTarget.get(targetDocumentId));
    }

    public boolean hasUnresolvedAdjustment(UUID sourceDocumentId) {
        return byTarget.values().stream().filter(value -> value.kind() == CorrectionKind.ADJUSTMENT
                        && value.sourceDocumentId().equals(sourceDocumentId))
                .map(value -> document(value.targetDocumentId()))
                .anyMatch(value -> value.getStatus() != VoucherStatus.CANCELLED && postedReversal(value.getId()).isEmpty());
    }

    public Optional<JournalEntry> postedReversal(UUID sourceDocumentId) {
        CorrectionRecord record = reversals.get(sourceDocumentId);
        return record == null ? Optional.empty() : Optional.ofNullable(journalBySource.get(record.targetDocumentId()));
    }

    public List<DebtAllocation> matching(CorrectionRecord record) {
        if (record.kind() != CorrectionKind.REVERSAL) { return List.of(); }
        JournalEntry original = journalBySource.get(record.sourceDocumentId());
        JournalEntry reversed = journalBySource.get(record.targetDocumentId());
        if (original == null || reversed == null) {
            throw new IllegalStateException("Both sides of reversal matching must be posted");
        }
        List<DebtAllocation> result = new ArrayList<>();
        for (JournalLine line : original.getLines()) {
            Account account = accounts.get(line.getAccountCode());
            if (account.getTracking() != AccountTracking.PARTNER
                    || (account.getType() != AccountType.ASSET && account.getType() != AccountType.LIABILITY)) {
                continue;
            }
            boolean sourceCharge = account.getType() == AccountType.ASSET
                    ? line.getSide() == BalanceSide.DEBIT : line.getSide() == BalanceSide.CREDIT;
            JournalLineKey sourceKey = new JournalLineKey(original.getId(), line.getLineNumber());
            JournalLineKey reversedKey = new JournalLineKey(reversed.getId(), line.getLineNumber());
            UUID id = UUID.nameUUIDFromBytes(("REVERSAL:" + record.id() + ":" + line.getLineNumber()).getBytes(StandardCharsets.UTF_8));
            AllocationRequest request = new AllocationRequest(id, sourceCharge ? sourceKey : reversedKey,
                    sourceCharge ? reversedKey : sourceKey, line.getAmount(), reversed.getPostingDate(), "Automatic reversal matching");
            result.add(new DebtAllocation(request, reversed.getPostedBy(), reversed.getPostedAt()));
        }
        return List.copyOf(result);
    }

    public void validateMatching(Collection<DebtAllocation> allocations, Collection<AllocationCancellation> cancellations) {
        Map<UUID, DebtAllocation> stored = allocations.stream().collect(Collectors.toMap(DebtAllocation::id, Function.identity()));
        Set<UUID> cancelled = cancellations.stream().map(AllocationCancellation::allocationId).collect(Collectors.toSet());
        for (CorrectionRecord record : reversals.values()) {
            if (postedReversal(record.sourceDocumentId()).isPresent()) {
                for (DebtAllocation expected : matching(record)) {
                    if (!expected.equals(stored.get(expected.id())) || cancelled.contains(expected.id())) {
                        throw new IllegalStateException("Posted reversal matching is mandatory and cannot be cancelled independently");
                    }
                }
            }
        }
    }

    private VoucherDocument document(UUID id) {
        VoucherDocument document = documents.get(id);
        if (document == null) { throw new IllegalArgumentException("Correction document not found: " + id); }
        return document;
    }
}
