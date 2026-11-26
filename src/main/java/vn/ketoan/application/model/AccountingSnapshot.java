package vn.ketoan.application.model;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.CorrectionBook;
import vn.ketoan.domain.CorrectionRecord;
import vn.ketoan.domain.ClosingBatch;
import vn.ketoan.domain.ClosingBook;
import vn.ketoan.domain.ClosingEvent;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLine;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.OpenItemBook;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PrepaidAllocationRecord;
import vn.ketoan.domain.PrepaidBook;
import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidPlanCancellation;
import vn.ketoan.domain.User;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;
import vn.ketoan.domain.report.CashFlowBook;
import vn.ketoan.domain.report.CashFlowClassification;
import vn.ketoan.domain.report.ReportArchive;
import vn.ketoan.domain.report.ReportPublication;
import vn.ketoan.domain.report.ReportVersion;

/** Immutable cross-repository snapshot used to check integrity for every storage adapter. */
public record AccountingSnapshot(List<Account> accounts, List<Partner> partners,
                               List<MoneyAccount> moneyAccounts, List<AccountingPeriod> periods,
                               List<User> users, List<VoucherDocument> documents, List<JournalEntry> journal,
                               List<DebtAllocation> allocations, List<AllocationCancellation> cancellations,
                               List<CorrectionRecord> corrections, List<PrepaidExpensePlan> prepaidPlans,
                               List<PrepaidPlanCancellation> prepaidCancellations, List<PrepaidAllocationRecord> prepaidAllocations,
                               List<ClosingBatch> closingBatches, List<ClosingEvent> closingEvents,
                               List<CashFlowClassification> cashFlowClassifications, List<ReportVersion> reportVersions,
                               List<ReportPublication> reportPublications) {

    public AccountingSnapshot {
        accounts = List.copyOf(accounts);
        partners = List.copyOf(partners);
        moneyAccounts = List.copyOf(moneyAccounts);
        periods = List.copyOf(periods);
        users = List.copyOf(users);
        documents = List.copyOf(documents);
        journal = List.copyOf(journal);
        allocations = List.copyOf(allocations);
        cancellations = List.copyOf(cancellations);
        corrections = List.copyOf(corrections);
        prepaidPlans = List.copyOf(prepaidPlans);
        prepaidCancellations = List.copyOf(prepaidCancellations);
        prepaidAllocations = List.copyOf(prepaidAllocations);
        closingBatches = List.copyOf(closingBatches);
        closingEvents = List.copyOf(closingEvents);
        cashFlowClassifications = List.copyOf(cashFlowClassifications);
        reportVersions = List.copyOf(reportVersions);
        reportPublications = List.copyOf(reportPublications);
    }

    public static AccountingSnapshot empty() {
        return new AccountingSnapshot(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    public static AccountingSnapshot capture(AccountingSession session) {
        return new AccountingSnapshot(session.accounts().findAll(), session.partners().findAll(),
                session.moneyAccounts().findAll(), session.periods().findAll(), session.users().findAll(),
                session.documents().findAll(), session.journal().findAll(), session.allocations().findAll(),
                session.allocations().findCancellations(), session.corrections().findAll(), session.prepaidPlans().findAll(),
                session.prepaidPlans().findCancellations(), session.prepaidAllocations().findAll(), session.closings().findAll(), session.closings().findEvents(),
                session.cashFlowClassifications().findAll(), session.reportVersions().findAll(), session.reportVersions().findPublications());
    }

    public void validateIntegrity() {
        Map<String, Account> accountIndex = accounts.stream()
                .collect(Collectors.toMap(Account::getCode, Function.identity()));
        Map<String, Partner> partnerIndex = partners.stream()
                .collect(Collectors.toMap(Partner::getCode, Function.identity()));
        Map<String, MoneyAccount> fundIndex = moneyAccounts.stream()
                .collect(Collectors.toMap(MoneyAccount::getCode, Function.identity()));
        Map<Long, User> userIndex = users.stream().collect(Collectors.toMap(User::getId, Function.identity()));
        Map<UUID, VoucherDocument> documentIndex = documents.stream()
                .collect(Collectors.toMap(VoucherDocument::getId, Function.identity()));
        Map<UUID, JournalEntry> sourceIndex = journal.stream()
                .collect(Collectors.toMap(JournalEntry::getSourceDocumentId, Function.identity()));
        for (VoucherDocument document : documents) {
            if (!userIndex.containsKey(document.getCreatedBy()) || document.getHistory().stream()
                    .anyMatch(event -> !userIndex.containsKey(event.actorId()))) {
                throw new IllegalStateException("Document history references an unknown user");
            }
            if (document.getStatus() == VoucherStatus.POSTED && !sourceIndex.containsKey(document.getId())) {
                throw new IllegalStateException("A posted document requires its journal entry");
            }
        }
        for (JournalEntry entry : journal) {
            VoucherDocument document = documentIndex.get(entry.getSourceDocumentId());
            if (document == null) {
                throw new IllegalStateException("A journal entry requires its source document");
            }
            entry.requireMatches(document);
            for (JournalLine line : entry.getLines()) {
                if (!accountIndex.containsKey(line.getAccountCode())) {
                    throw new IllegalStateException("Journal account does not exist");
                }
                if (line.getPartnerCode().filter(code -> !partnerIndex.containsKey(code)).isPresent()) {
                    throw new IllegalStateException("Journal partner does not exist");
                }
                line.getMoneyAccountCode().ifPresent(code -> {
                    MoneyAccount fund = fundIndex.get(code);
                    if (fund == null || !fund.getLedgerAccountCode().equals(line.getAccountCode())) {
                        throw new IllegalStateException("Journal money account binding is inconsistent");
                    }
                });
            }
        }
        if (allocations.stream().anyMatch(value -> !userIndex.containsKey(value.recordedBy()))
                || cancellations.stream().anyMatch(value -> !userIndex.containsKey(value.recordedBy()))) {
            throw new IllegalStateException("Allocation history references an unknown user");
        }
        new OpenItemBook(accounts, journal, allocations, cancellations);
        if (corrections.stream().anyMatch(value -> !userIndex.containsKey(value.createdBy()))
                || prepaidPlans.stream().anyMatch(value -> !userIndex.containsKey(value.createdBy()))
                || prepaidCancellations.stream().anyMatch(value -> !userIndex.containsKey(value.recordedBy()))
                || prepaidAllocations.stream().anyMatch(value -> !userIndex.containsKey(value.createdBy()))) {
            throw new IllegalStateException("Correction or prepaid history references an unknown user");
        }
        CorrectionBook correctionBook = new CorrectionBook(accounts, documents, journal, corrections);
        correctionBook.validateMatching(allocations, cancellations);
        new PrepaidBook(accounts, documents, journal, prepaidPlans, prepaidCancellations, prepaidAllocations, correctionBook);
        if (closingBatches.stream().anyMatch(value -> !userIndex.containsKey(value.preparedBy()))
                || closingEvents.stream().anyMatch(value -> !userIndex.containsKey(value.actorId()))) {
            throw new IllegalStateException("Closing history references an unknown user");
        }
        new ClosingBook(accounts, periods, documents, journal, closingBatches, closingEvents, corrections, prepaidPlans, prepaidAllocations);
        if (cashFlowClassifications.stream().anyMatch(value -> !userIndex.containsKey(value.recordedBy()))
                || reportVersions.stream().anyMatch(value -> !userIndex.containsKey(value.createdBy()))
                || reportPublications.stream().anyMatch(value -> !userIndex.containsKey(value.publishedBy()))) {
            throw new IllegalStateException("Cash flow or report history references an unknown user");
        }
        new CashFlowBook(accounts, journal, corrections, cashFlowClassifications);
        new ReportArchive(reportVersions, reportPublications);
    }
}
