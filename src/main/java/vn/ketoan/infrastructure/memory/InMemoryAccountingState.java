package vn.ketoan.infrastructure.memory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import vn.ketoan.domain.Account;
import vn.ketoan.domain.AccountingPeriod;
import vn.ketoan.domain.AllocationCancellation;
import vn.ketoan.domain.DebtAllocation;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.JournalLine;
import vn.ketoan.domain.MoneyAccount;
import vn.ketoan.domain.OpenItemBook;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.User;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;

/** Immutable committed membership; every entity and nested collection is also immutable. */
record InMemoryAccountingState(List<Account> accounts, List<Partner> partners,
                               List<MoneyAccount> moneyAccounts, List<AccountingPeriod> periods,
                               List<User> users, List<VoucherDocument> documents, List<JournalEntry> journal,
                               List<DebtAllocation> allocations, List<AllocationCancellation> cancellations) {

    InMemoryAccountingState {
        accounts = List.copyOf(accounts);
        partners = List.copyOf(partners);
        moneyAccounts = List.copyOf(moneyAccounts);
        periods = List.copyOf(periods);
        users = List.copyOf(users);
        documents = List.copyOf(documents);
        journal = List.copyOf(journal);
        allocations = List.copyOf(allocations);
        cancellations = List.copyOf(cancellations);
    }

    static InMemoryAccountingState empty() {
        return new InMemoryAccountingState(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(), List.of());
    }

    void validateIntegrity() {
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
    }
}
