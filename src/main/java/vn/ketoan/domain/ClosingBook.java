package vn.ketoan.domain;

import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Verifies the frozen basis, generated content, review decisions and atomic completion of every closing. */
public final class ClosingBook {
    private final Map<UUID, ClosingBatch> batches;
    private final Map<UUID, ClosingStatus> statuses = new HashMap<>();

    public ClosingBook(Collection<Account> accounts, Collection<AccountingPeriod> periods,
                       Collection<VoucherDocument> documents, Collection<JournalEntry> journal,
                       Collection<ClosingBatch> batches, Collection<ClosingEvent> events,
                       Collection<CorrectionRecord> corrections, Collection<PrepaidExpensePlan> prepaidPlans,
                       Collection<PrepaidAllocationRecord> installments) {
        this.batches = batches.stream().collect(Collectors.toMap(ClosingBatch::id, Function.identity()));
        var periodIndex = periods.stream().collect(Collectors.toMap(AccountingPeriod::getMonth, Function.identity()));
        var documentIndex = documents.stream().collect(Collectors.toMap(VoucherDocument::getId, Function.identity()));
        var journalIndex = journal.stream().collect(Collectors.toMap(JournalEntry::getId, Function.identity()));
        var closingDocuments = new HashSet<UUID>();
        for (ClosingBatch batch : batches) {
            for (UUID id : batch.documentIds()) {
                if (!closingDocuments.add(id)) { throw new IllegalArgumentException("A voucher cannot belong to two closings"); }
            }
        }
        for (ClosingEvent event : events) {
            if (!this.batches.containsKey(event.batchId())) { throw new IllegalArgumentException("Closing event requires its batch"); }
        }
        if (corrections.stream().anyMatch(value -> closingDocuments.contains(value.sourceDocumentId()) || closingDocuments.contains(value.targetDocumentId()))
                || installments.stream().anyMatch(value -> closingDocuments.contains(value.targetDocumentId()))
                || prepaidPlans.stream().anyMatch(value -> journalIndex.containsKey(value.request().source().journalEntryId())
                && closingDocuments.contains(journalIndex.get(value.request().source().journalEntryId()).getSourceDocumentId()))) {
            throw new IllegalStateException("Closing vouchers are managed only through the closing workflow");
        }
        Map<YearMonth, UUID> live = new HashMap<>();
        ClosingCalculator calculator = new ClosingCalculator();
        for (ClosingBatch batch : batches) {
            ClosingStatus status = status(batch, events);
            statuses.put(batch.id(), status);
            AccountingPeriod period = periodIndex.get(batch.request().month());
            if (period == null) { throw new IllegalArgumentException("Closing period not found"); }
            if (status != ClosingStatus.CANCELLED) {
                if (live.putIfAbsent(batch.request().month(), batch.id()) != null) {
                    throw new IllegalStateException("Only one live closing is allowed for a month");
                }
                PeriodStatus expected = status == ClosingStatus.CLOSED ? PeriodStatus.CLOSED : PeriodStatus.CLOSING;
                if (period.getStatus() != expected) { throw new IllegalStateException("Period status does not match its closing"); }
                var actual = journal.stream().filter(entry -> batch.request().month().equals(YearMonth.from(entry.getPostingDate()))
                                && !closingDocuments.contains(entry.getSourceDocumentId())).map(JournalEntry::getId).collect(Collectors.toSet());
                if (!actual.equals(new HashSet<>(batch.sourceEntryIds()))) {
                    throw new IllegalStateException("The journal basis changed after closing was prepared");
                }
                if (periods.stream().anyMatch(value -> value.getMonth().isBefore(batch.request().month()) && value.getStatus() != PeriodStatus.CLOSED)) {
                    throw new IllegalStateException("Close earlier accounting periods first");
                }
                calculator.requireZeroTemporaryBalances(accounts, journal, batch.request().month().atDay(1).minusDays(1));
            }
            List<JournalEntry> sources = batch.sourceEntryIds().stream().map(id -> {
                JournalEntry entry = journalIndex.get(id);
                if (entry == null || closingDocuments.contains(entry.getSourceDocumentId()) || entry.getPostedAt().isAfter(batch.preparedAt())) {
                    throw new IllegalArgumentException("Closing requires ordinary entries posted before preparation");
                }
                return entry;
            }).toList();
            ClosingPreview preview = calculator.calculate(batch.request(), accounts, sources);
            if (!preview.sourceChecksum().equals(batch.sourceChecksum()) || !preview.revenue().equals(batch.revenue())
                    || !preview.expense().equals(batch.expense()) || !preview.profit().equals(batch.profit())
                    || preview.vouchers().size() != batch.documentIds().size()) {
                throw new IllegalArgumentException("Stored closing basis and totals are inconsistent");
            }
            VoucherStatus expected = switch (status) {
                case PREPARED -> VoucherStatus.PENDING_APPROVAL;
                case APPROVED -> VoucherStatus.APPROVED;
                case CLOSED -> VoucherStatus.POSTED;
                case CANCELLED -> VoucherStatus.CANCELLED;
            };
            var decisions = events.stream().filter(value -> value.batchId().equals(batch.id())).toList();
            for (int index = 0; index < batch.documentIds().size(); index++) {
                VoucherDocument target = documentIndex.get(batch.documentIds().get(index));
                VoucherSnapshot content = VoucherSnapshot.from(new GeneralCreator().create(preview.vouchers().get(index)));
                if (target == null || target.getCreatedBy() != batch.preparedBy() || !target.getCreatedAt().equals(batch.preparedAt())
                        || !content.equals(VoucherSnapshot.from(target.getContent())) || target.getStatus() != expected) {
                    throw new IllegalStateException("Closing vouchers are frozen and must move through the batch workflow together");
                }
                for (ClosingEvent event : decisions) {
                    VoucherAction action = switch (event.action()) {
                        case APPROVED -> VoucherAction.APPROVED;
                        case CLOSED -> VoucherAction.POSTED;
                        case CANCELLED -> VoucherAction.CANCELLED;
                    };
                    var history = target.getHistory().stream().filter(value -> value.action() == action).findFirst().orElseThrow(
                            () -> new IllegalStateException("Closing decisions require matching voucher history"));
                    if (history.actorId() != event.actorId() || !history.occurredAt().equals(event.occurredAt())
                            || (action == VoucherAction.CANCELLED && !history.note().equals(event.reason()))) {
                        throw new IllegalStateException("Closing decisions must match each voucher's actor, time and cancellation reason");
                    }
                }
            }
            if (status == ClosingStatus.CLOSED) {
                calculator.requireZeroTemporaryBalances(accounts, journal, batch.request().month().atEndOfMonth());
            }
        }
    }

    public ClosingStatus status(UUID id) {
        if (!batches.containsKey(id)) { throw new IllegalArgumentException("Closing batch not found: " + id); }
        return statuses.get(id);
    }

    private ClosingStatus status(ClosingBatch batch, Collection<ClosingEvent> events) {
        ClosingStatus status = ClosingStatus.PREPARED;
        java.time.Instant previous = batch.preparedAt();
        var matching = events.stream().filter(event -> event.batchId().equals(batch.id()))
                .sorted(java.util.Comparator.comparingInt(event -> switch (event.action()) { case APPROVED -> 0; case CLOSED -> 1; case CANCELLED -> 2; })).toList();
        var actions = new HashSet<ClosingAction>();
        for (ClosingEvent event : matching) {
            if (!actions.add(event.action()) || event.actorId() == batch.preparedBy() || event.occurredAt().isBefore(previous)) {
                throw new IllegalArgumentException("Closing decisions require an independent actor and chronological, unique actions");
            }
            status = switch (event.action()) {
                case APPROVED -> {
                    if (status != ClosingStatus.PREPARED) { throw new IllegalStateException("Invalid closing approval"); }
                    yield ClosingStatus.APPROVED;
                }
                case CLOSED -> {
                    if (status != ClosingStatus.APPROVED) { throw new IllegalStateException("Review the closing before completion"); }
                    yield ClosingStatus.CLOSED;
                }
                case CANCELLED -> {
                    if (status != ClosingStatus.PREPARED && status != ClosingStatus.APPROVED) { throw new IllegalStateException("A completed closing cannot be cancelled"); }
                    yield ClosingStatus.CANCELLED;
                }
            };
            previous = event.occurredAt();
        }
        return status;
    }
}
