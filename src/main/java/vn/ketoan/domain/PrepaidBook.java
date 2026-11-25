package vn.ketoan.domain;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import vn.ketoan.domain.value.Money;

/** Frozen schedules, posting order, dated asset budgets and dated expense projections. */
public final class PrepaidBook {

    private final Map<UUID, PrepaidExpensePlan> plans;
    private final Map<UUID, PrepaidPlanCancellation> cancellations;
    private final Map<UUID, VoucherDocument> documents;
    private final Map<UUID, JournalEntry> journal;
    private final Map<UUID, PrepaidAllocationRecord> allocations;
    private final CorrectionBook corrections;

    public PrepaidBook(Collection<Account> accounts, Collection<VoucherDocument> documents, Collection<JournalEntry> journal,
                       Collection<PrepaidExpensePlan> plans, Collection<PrepaidPlanCancellation> cancellations,
                       Collection<PrepaidAllocationRecord> allocations, CorrectionBook corrections) {
        this.plans = plans.stream().collect(Collectors.toMap(PrepaidExpensePlan::id, Function.identity()));
        this.cancellations = cancellations.stream().collect(Collectors.toMap(PrepaidPlanCancellation::planId, Function.identity()));
        this.documents = documents.stream().collect(Collectors.toMap(VoucherDocument::getId, Function.identity()));
        this.journal = journal.stream().collect(Collectors.toMap(JournalEntry::getId, Function.identity()));
        this.allocations = allocations.stream().collect(Collectors.toMap(PrepaidAllocationRecord::id, Function.identity()));
        this.corrections = corrections;
        Map<String, Account> accountIndex = accounts.stream().collect(Collectors.toMap(Account::getCode, Function.identity()));
        Map<JournalLineKey, UUID> activePlans = new HashMap<>();
        for (PrepaidExpensePlan plan : plans) {
            JournalEntry entry = source(plan);
            JournalLine line = sourceLine(plan);
            Account asset = accountIndex.get(line.getAccountCode());
            Account expense = accountIndex.get(plan.request().expenseAccountCode());
            if (corrections.hasUnresolvedAdjustment(entry.getSourceDocumentId())) {
                throw new IllegalStateException("A prepaid source with a schedule must be corrected by reversal and a new plan");
            }
            if (asset == null || asset.getType() != AccountType.ASSET || asset.getTracking() != AccountTracking.NONE
                    || line.getSide() != BalanceSide.DEBIT || line.getPartnerCode().isPresent() || line.getMoneyAccountCode().isPresent()
                    || expense == null || expense.getType() != AccountType.EXPENSE || expense.getTracking() != AccountTracking.NONE
                    || !expense.isPostingAllowed() || !plan.total().equals(line.getAmount())
                    || plan.request().firstMonth().isBefore(YearMonth.from(entry.getPostingDate()))
                    || plan.createdAt().isBefore(entry.getPostedAt())) {
                throw new IllegalArgumentException("Prepaid plans require a posted untracked asset debit and a matching expense schedule");
            }
            if (!this.cancellations.containsKey(plan.id())) {
                if (activePlans.putIfAbsent(plan.request().source(), plan.id()) != null
                        || corrections.activeReversal(entry.getSourceDocumentId()).isPresent()) {
                    throw new IllegalStateException("Only one active plan can reserve a source that is not being reversed");
                }
            }
        }
        Map<PeriodKey, VoucherDocument> activePeriods = new HashMap<>();
        for (PrepaidAllocationRecord record : allocations) {
            PrepaidExpensePlan plan = plan(record.planId());
            VoucherDocument target = document(record.targetDocumentId());
            if (corrections.correctionOf(target.getId()).isPresent()) {
                throw new IllegalArgumentException("A generated installment cannot also be a correction target");
            }
            if (corrections.hasUnresolvedAdjustment(target.getId())) {
                throw new IllegalStateException("Reverse a generated installment before replacing it; its frozen amount cannot receive a delta");
            }
            PrepaidAllocationRequest request = new PrepaidAllocationRequest(record.id(), plan.id(), target.getContent().getReference(),
                    target.getHistory().getFirst().content().postingDate());
            VoucherSnapshot expected = VoucherSnapshot.from(new GeneralCreator().create(draft(plan, request)));
            if (target.getCreatedBy() != record.createdBy() || !target.getCreatedAt().equals(record.createdAt())
                    || record.createdAt().isBefore(plan.createdAt()) || !record.month().equals(YearMonth.from(request.postingDate()))
                    || request.postingDate().isBefore(source(plan).getPostingDate())
                    || !expected.equals(VoucherSnapshot.from(target.getContent()))) {
                throw new IllegalArgumentException("Prepaid installment content is generated from its frozen schedule and cannot be edited");
            }
            if (target.getStatus() != VoucherStatus.CANCELLED && corrections.postedReversal(target.getId()).isEmpty()
                    && activePeriods.putIfAbsent(new PeriodKey(plan.id(), record.month()), target) != null) {
                throw new IllegalStateException("A prepaid period can have only one live installment");
            }
        }
        for (PrepaidPlanCancellation cancellation : cancellations) {
            PrepaidExpensePlan plan = plan(cancellation.planId());
            if (cancellation.recordedAt().isBefore(plan.createdAt())
                    || cancellation.effectiveDate().isBefore(source(plan).getPostingDate())) {
                throw new IllegalArgumentException("Plan cancellation cannot precede its creation or source");
            }
            for (PrepaidAllocationRecord record : allocations) {
                if (!record.planId().equals(plan.id())) { continue; }
                VoucherDocument target = document(record.targetDocumentId());
                if (record.createdAt().isAfter(cancellation.recordedAt())
                        || (target.getStatus() != VoucherStatus.POSTED && target.getStatus() != VoucherStatus.CANCELLED)
                        || (target.getStatus() == VoucherStatus.POSTED
                        && cancellation.effectiveDate().isBefore(target.getContent().getPostingDate()))) {
                    throw new IllegalStateException("Resolve pending installment vouchers before cancelling a plan; its cancellation must follow posted periods");
                }
            }
        }
        for (PrepaidExpensePlan plan : plans) {
            for (PrepaidExpensePeriod period : plan.schedule()) {
                VoucherDocument target = activePeriods.get(new PeriodKey(plan.id(), period.month()));
                if (target != null && target.getStatus() == VoucherStatus.POSTED) {
                    requirePrevious(plan, period.month(), activePeriods);
                }
            }
            if (!this.cancellations.containsKey(plan.id())) {
                for (PrepaidExpensePlan other : plans) {
                    if (!other.id().equals(plan.id()) && other.request().source().equals(plan.request().source())
                            && recognized(other.id(), LocalDate.MAX).isPositive()) {
                        throw new IllegalStateException("Reverse the earlier plan's recognized installments before reserving its source again");
                    }
                }
            }
        }
        validateBudgets();
    }

    public Draft draft(PrepaidExpensePlan plan, PrepaidAllocationRequest request) {
        YearMonth month = YearMonth.from(request.postingDate());
        Money amount = plan.period(month).amount();
        return new Draft(request.reference(), request.postingDate(), request.postingDate(),
                "Prepaid " + plan.request().reference() + " " + month, List.of(
                VoucherLine.debit(plan.request().expenseAccountCode(), amount, null, null),
                VoucherLine.credit(sourceLine(plan).getAccountCode(), amount, null, null)));
    }

    public PrepaidExpenseStatement statement(UUID id, LocalDate asOf) {
        DomainValidation.date(asOf, "Prepaid statement date");
        PrepaidExpensePlan plan = plan(id);
        Money recognized = recognized(id, asOf);
        Money available = source(plan).getPostingDate().isAfter(asOf) ? Money.ZERO : plan.total();
        var reversal = corrections.postedReversal(source(plan).getSourceDocumentId());
        if (reversal.filter(value -> !value.getPostingDate().isAfter(asOf)).isPresent()) { available = Money.ZERO; }
        PrepaidPlanCancellation cancellation = cancellations.get(id);
        List<PrepaidExpenseStatement.Row> rows = plan.schedule().stream().map(period -> new PrepaidExpenseStatement.Row(
                period.month(), period.amount(), recognized(id, period.month(), asOf))).toList();
        return new PrepaidExpenseStatement(id, plan.request().reference(), asOf, plan.total(), recognized,
                available.subtract(recognized), cancellation != null && !cancellation.effectiveDate().isAfter(asOf), rows);
    }

    public void requirePosting(UUID documentId) {
        PrepaidAllocationRecord record = allocations.values().stream()
                .filter(value -> value.targetDocumentId().equals(documentId)).findFirst().orElseThrow();
        PrepaidExpensePlan plan = plan(record.planId());
        if (cancellations.containsKey(plan.id())) {
            throw new IllegalStateException("A cancelled prepaid plan cannot post installments");
        }
        if (!record.month().equals(plan.request().firstMonth())) {
            boolean previousPosted = allocations.values().stream().filter(value -> value.planId().equals(plan.id())
                            && value.month().equals(record.month().minusMonths(1)))
                    .map(value -> document(value.targetDocumentId()))
                    .anyMatch(value -> value.getStatus() == VoucherStatus.POSTED && corrections.activeReversal(value.getId()).isEmpty());
            if (!previousPosted) { throw new IllegalStateException("Post the previous prepaid month first"); }
        }
    }

    private void requirePrevious(PrepaidExpensePlan plan, YearMonth month, Map<PeriodKey, VoucherDocument> active) {
        if (month.equals(plan.request().firstMonth())) { return; }
        VoucherDocument previous = active.get(new PeriodKey(plan.id(), month.minusMonths(1)));
        if (previous == null || previous.getStatus() != VoucherStatus.POSTED || corrections.activeReversal(previous.getId()).isPresent()) {
            throw new IllegalStateException("Post prepaid months in order; reverse later installments before earlier ones");
        }
    }

    private void validateBudgets() {
        Map<JournalLineKey, NavigableMap<LocalDate, Money>> assets = new HashMap<>();
        Map<PeriodKey, NavigableMap<LocalDate, Money>> periods = new HashMap<>();
        for (PrepaidExpensePlan plan : plans.values()) {
            if (!assets.containsKey(plan.request().source())) {
                NavigableMap<LocalDate, Money> changes = new TreeMap<>();
                changes.merge(source(plan).getPostingDate(), plan.total(), Money::add);
                corrections.postedReversal(source(plan).getSourceDocumentId()).ifPresent(reversal ->
                        changes.merge(reversal.getPostingDate(), plan.total().negate(), Money::add));
                assets.put(plan.request().source(), changes);
            }
        }
        for (PrepaidAllocationRecord record : allocations.values()) {
            VoucherDocument target = document(record.targetDocumentId());
            if (target.getStatus() != VoucherStatus.POSTED) { continue; }
            PrepaidExpensePlan plan = plan(record.planId());
            Money amount = plan.period(record.month()).amount();
            NavigableMap<LocalDate, Money> asset = assets.get(plan.request().source());
            NavigableMap<LocalDate, Money> period = periods.computeIfAbsent(new PeriodKey(plan.id(), record.month()), ignored -> new TreeMap<>());
            asset.merge(target.getContent().getPostingDate(), amount.negate(), Money::add);
            period.merge(target.getContent().getPostingDate(), amount, Money::add);
            corrections.postedReversal(target.getId()).ifPresent(reversal -> {
                asset.merge(reversal.getPostingDate(), amount, Money::add);
                period.merge(reversal.getPostingDate(), amount.negate(), Money::add);
            });
        }
        assets.values().forEach(timeline -> requireBounds(timeline, null));
        periods.forEach((key, timeline) -> requireBounds(timeline, plan(key.planId()).period(key.month()).amount()));
    }

    private void requireBounds(NavigableMap<LocalDate, Money> timeline, Money maximum) {
        Money current = Money.ZERO;
        for (Money change : timeline.values()) {
            current = current.add(change);
            if (current.isNegative() || (maximum != null && current.compareTo(maximum) > 0)) {
                throw new IllegalStateException("Prepaid postings exceed their source or recognize a month more than once at an effective date");
            }
        }
    }

    private Money recognized(UUID planId, LocalDate asOf) {
        return plan(planId).schedule().stream().map(period -> recognized(planId, period.month(), asOf)).reduce(Money.ZERO, Money::add);
    }

    private Money recognized(UUID planId, YearMonth month, LocalDate asOf) {
        Money total = Money.ZERO;
        for (PrepaidAllocationRecord record : allocations.values()) {
            if (!record.planId().equals(planId) || !record.month().equals(month)) { continue; }
            VoucherDocument target = document(record.targetDocumentId());
            if (target.getStatus() == VoucherStatus.POSTED && !target.getContent().getPostingDate().isAfter(asOf)) {
                Money amount = plan(planId).period(month).amount();
                total = total.add(amount);
                if (corrections.postedReversal(target.getId()).filter(value -> !value.getPostingDate().isAfter(asOf)).isPresent()) {
                    total = total.subtract(amount);
                }
            }
        }
        return total;
    }

    private PrepaidExpensePlan plan(UUID id) {
        PrepaidExpensePlan plan = plans.get(id);
        if (plan == null) { throw new IllegalArgumentException("Prepaid plan not found: " + id); }
        return plan;
    }

    private JournalEntry source(PrepaidExpensePlan plan) {
        JournalEntry entry = journal.get(plan.request().source().journalEntryId());
        if (entry == null) { throw new IllegalArgumentException("Prepaid source must be a posted journal entry"); }
        return entry;
    }

    private JournalLine sourceLine(PrepaidExpensePlan plan) {
        return source(plan).getLines().stream().filter(line -> line.getLineNumber() == plan.request().source().lineNumber())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Prepaid source line does not exist"));
    }

    private VoucherDocument document(UUID id) {
        VoucherDocument document = documents.get(id);
        if (document == null) { throw new IllegalArgumentException("Prepaid installment document not found: " + id); }
        return document;
    }

    private record PeriodKey(UUID planId, YearMonth month) { }
}
