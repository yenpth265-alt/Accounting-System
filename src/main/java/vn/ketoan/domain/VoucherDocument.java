package vn.ketoan.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/** Immutable workflow aggregate: content, state, version and history move together. */
public final class VoucherDocument {

    private final UUID id;
    private final Voucher content;
    private final long createdBy;
    private final VoucherStatus status;
    private final long version;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<VoucherHistoryEntry> history;

    private VoucherDocument(UUID id, Voucher content, long createdBy, VoucherStatus status,
                            long version, Instant createdAt, Instant updatedAt,
                            List<VoucherHistoryEntry> history) {
        this.id = Objects.requireNonNull(id, "Document id is required");
        this.content = Objects.requireNonNull(content, "Voucher content is required");
        this.createdBy = createdBy;
        this.status = Objects.requireNonNull(status, "Voucher status is required");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time is required");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Update time is required");
        this.history = List.copyOf(history);
        if (createdBy <= 0 || version < 0 || this.history.size() != version + 1) {
            throw new IllegalArgumentException("Document owner, version and history are inconsistent");
        }
        VoucherHistoryEntry last = this.history.getLast();
        if (last.version() != version || last.toStatus() != status || !last.occurredAt().equals(updatedAt)
                || !last.content().equals(VoucherSnapshot.from(content)) || updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Document does not match its latest history entry");
        }
    }

    public static VoucherDocument create(UUID id, Voucher content, User actor, Instant time) {
        Objects.requireNonNull(actor, "Actor is required").requirePermission(Permission.CREATE_VOUCHER);
        VoucherHistoryEntry entry = new VoucherHistoryEntry(0, VoucherAction.CREATED, actor.getId(),
                null, VoucherStatus.DRAFT, time, "Created draft", VoucherSnapshot.from(content));
        return new VoucherDocument(id, content, actor.getId(), VoucherStatus.DRAFT,
                0, time, time, List.of(entry));
    }

    public void requireActionAllowed(User actor, VoucherAction action) {
        Objects.requireNonNull(actor, "Actor is required");
        Objects.requireNonNull(action, "Action is required");
        actor.requirePermission(action.getPermission());
        switch (action) {
            case CREATED -> throw new IllegalStateException("Use the creation operation for a new document");
            case EDITED, SUBMITTED -> {
                requireOwner(actor);
                requireStatus(VoucherStatus.DRAFT, VoucherStatus.RETURNED);
            }
            case RETURNED, APPROVED -> {
                requireIndependentReviewer(actor);
                requireStatus(VoucherStatus.PENDING_APPROVAL);
            }
            case APPROVAL_WITHDRAWN -> {
                requireIndependentReviewer(actor);
                requireStatus(VoucherStatus.APPROVED);
            }
            case CANCELLED -> {
                if (!actor.hasRole(UserRole.CHIEF)) {
                    requireOwner(actor);
                }
                requireStatus(VoucherStatus.DRAFT, VoucherStatus.RETURNED);
            }
        }
    }

    private void requireOwner(User actor) {
        if (actor.getId() != createdBy) {
            throw new AccessDeniedException("Only the creator may edit or submit this voucher");
        }
    }

    private void requireIndependentReviewer(User actor) {
        if (actor.getId() == createdBy) {
            throw new AccessDeniedException("The creator cannot review their own voucher");
        }
    }

    private void requireStatus(VoucherStatus... allowed) {
        if (Arrays.stream(allowed).noneMatch(value -> value == status)) {
            throw new IllegalStateException("Action is not allowed while voucher is " + status);
        }
    }

    public VoucherDocument edit(User actor, Voucher replacement, Instant time) {
        Objects.requireNonNull(replacement, "Replacement content is required");
        if (replacement.getType() != content.getType()
                || !replacement.getReference().equals(content.getReference())) {
            throw new IllegalArgumentException("Editing cannot change the voucher type or reference");
        }
        return change(actor, VoucherAction.EDITED, replacement, status, time, "Updated draft content");
    }

    public VoucherDocument submit(User actor, Instant time) {
        return change(actor, VoucherAction.SUBMITTED, content, VoucherStatus.PENDING_APPROVAL,
                time, "Submitted for approval");
    }

    public VoucherDocument returnForRevision(User actor, String reason, Instant time) {
        return change(actor, VoucherAction.RETURNED, content, VoucherStatus.RETURNED, time, reason);
    }

    public VoucherDocument approve(User actor, Instant time) {
        return change(actor, VoucherAction.APPROVED, content, VoucherStatus.APPROVED,
                time, "Approved content");
    }

    public VoucherDocument withdrawApproval(User actor, String reason, Instant time) {
        return change(actor, VoucherAction.APPROVAL_WITHDRAWN, content, VoucherStatus.RETURNED, time, reason);
    }

    public VoucherDocument cancel(User actor, String reason, Instant time) {
        return change(actor, VoucherAction.CANCELLED, content, VoucherStatus.CANCELLED, time, reason);
    }

    private VoucherDocument change(User actor, VoucherAction action, Voucher nextContent,
                                   VoucherStatus nextStatus, Instant time, String note) {
        requireActionAllowed(actor, action);
        Objects.requireNonNull(time, "Action time is required");
        if (time.isBefore(updatedAt)) {
            throw new IllegalArgumentException("Action time cannot precede the previous action");
        }
        long nextVersion = Math.incrementExact(version);
        List<VoucherHistoryEntry> nextHistory = new ArrayList<>(history);
        nextHistory.add(new VoucherHistoryEntry(nextVersion, action, actor.getId(), status,
                nextStatus, time, note, VoucherSnapshot.from(nextContent)));
        return new VoucherDocument(id, nextContent, createdBy, nextStatus,
                nextVersion, createdAt, time, nextHistory);
    }

    public UUID getId() {
        return id;
    }

    public Voucher getContent() {
        return content;
    }

    public long getCreatedBy() {
        return createdBy;
    }

    public VoucherStatus getStatus() {
        return status;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public List<VoucherHistoryEntry> getHistory() {
        return history;
    }

    public OptionalLong getApprovedBy() {
        return status == VoucherStatus.APPROVED
                ? OptionalLong.of(history.getLast().actorId()) : OptionalLong.empty();
    }

    public Optional<Instant> getApprovedAt() {
        return status == VoucherStatus.APPROVED ? Optional.of(updatedAt) : Optional.empty();
    }
}
