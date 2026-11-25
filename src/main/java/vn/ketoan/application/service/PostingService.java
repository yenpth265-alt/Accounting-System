package vn.ketoan.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.application.port.PostingPolicy;
import vn.ketoan.application.port.UnitOfWork;
import vn.ketoan.application.port.VoucherValidatorFactory;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.JournalEntry;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.User;
import vn.ketoan.domain.VoucherAction;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherStatus;

public final class PostingService {

    private final UnitOfWork transactions;
    private final VoucherValidatorFactory validators;
    private final Clock clock;
    private final List<PostingPolicy> policies;

    public PostingService(UnitOfWork transactions, VoucherValidatorFactory validators, Clock clock) {
        this(transactions, validators, clock, PostingPolicies.standard());
    }

    public PostingService(UnitOfWork transactions, VoucherValidatorFactory validators, Clock clock, List<PostingPolicy> policies) {
        this.transactions = Objects.requireNonNull(transactions, "Unit of work is required");
        this.validators = Objects.requireNonNull(validators, "Validator factory is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
        this.policies = List.copyOf(Objects.requireNonNull(policies, "Posting policies are required"));
    }

    public JournalEntry post(UUID documentId, long expectedVersion, long actorId) {
        Objects.requireNonNull(documentId, "Source document id is required");
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Expected version cannot be negative");
        }
        return transactions.execute(session -> {
            User actor = session.users().findById(actorId).orElseThrow(
                    () -> new AccessDeniedException("Unknown user: " + actorId));
            actor.requirePermission(Permission.POST_VOUCHER);
            VoucherDocument document = session.documents().findById(documentId).orElseThrow(
                    () -> new IllegalArgumentException("Voucher document not found: " + documentId));
            document.requirePostingActor(actor);
            Optional<JournalEntry> existing = session.journal().findBySourceDocumentId(documentId);
            if (document.getStatus() == VoucherStatus.POSTED) {
                JournalEntry entry = existing.orElseThrow(
                        () -> new IllegalStateException("Posted document is missing its journal entry"));
                entry.requireMatches(document);
                if (expectedVersion != entry.getSourceVersion() && expectedVersion != document.getVersion()) {
                    throw new StaleVersionException(documentId, expectedVersion, document.getVersion());
                }
                return entry;
            }
            if (document.getVersion() != expectedVersion) {
                throw new StaleVersionException(documentId, expectedVersion, document.getVersion());
            }
            if (existing.isPresent()) {
                throw new IllegalStateException("An unposted document cannot have a journal entry");
            }
            document.requireActionAllowed(actor, VoucherAction.POSTED);
            Objects.requireNonNull(validators.create(session), "Validator is required").validate(document.getContent());
            policies.forEach(policy -> policy.beforePost(session, document));
            Instant time = clock.instant();
            JournalEntry entry = JournalEntry.fromApproved(UUID.randomUUID(), document, actor, time);
            session.journal().append(entry);
            session.documents().update(document.markPosted(actor, time), expectedVersion);
            policies.forEach(policy -> policy.afterPost(session, entry));
            return entry;
        });
    }
}
