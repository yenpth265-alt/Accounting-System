package vn.ketoan.application.service;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import vn.ketoan.application.port.StaleVersionException;
import vn.ketoan.application.port.UserRepository;
import vn.ketoan.application.port.VoucherDocumentRepository;
import vn.ketoan.application.port.VoucherValidator;
import vn.ketoan.domain.AccessDeniedException;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.Permission;
import vn.ketoan.domain.User;
import vn.ketoan.domain.Voucher;
import vn.ketoan.domain.VoucherAction;
import vn.ketoan.domain.VoucherCreator;
import vn.ketoan.domain.VoucherDocument;
import vn.ketoan.domain.VoucherHistoryEntry;

/** Authorization/workflow boundary. Actor ids must come from a trusted caller/session. */
public final class VoucherWorkflowService {

    private final UserRepository users;
    private final VoucherDocumentRepository documents;
    private final VoucherValidator validator;
    private final Clock clock;

    public VoucherWorkflowService(UserRepository users, VoucherDocumentRepository documents,
                                   VoucherValidator validator, Clock clock) {
        this.users = Objects.requireNonNull(users, "User repository is required");
        this.documents = Objects.requireNonNull(documents, "Document repository is required");
        this.validator = Objects.requireNonNull(validator, "Voucher validator is required");
        this.clock = Objects.requireNonNull(clock, "Clock is required");
    }

    public VoucherDocument create(VoucherCreator creator, Draft input, long actorId) {
        User actor = requireActor(actorId, Permission.CREATE_VOUCHER);
        Voucher content = Objects.requireNonNull(creator, "Creator is required").create(input);
        VoucherDocument document = VoucherDocument.create(UUID.randomUUID(), content, actor, clock.instant());
        documents.insert(document);
        return document;
    }

    public VoucherDocument get(UUID id, long actorId) {
        requireActor(actorId, Permission.VIEW_VOUCHERS);
        return load(id);
    }

    public List<VoucherDocument> list(long actorId) {
        requireActor(actorId, Permission.VIEW_VOUCHERS);
        return List.copyOf(documents.findAll());
    }

    public List<VoucherHistoryEntry> history(UUID id, long actorId) {
        return get(id, actorId).getHistory();
    }

    public VoucherDocument edit(UUID id, long expectedVersion, VoucherCreator creator,
                                 Draft input, long actorId) {
        User actor = requireActor(actorId, Permission.EDIT_VOUCHER);
        VoucherDocument document = loadExpected(id, expectedVersion);
        document.requireActionAllowed(actor, VoucherAction.EDITED);
        Voucher replacement = Objects.requireNonNull(creator, "Creator is required").create(input);
        return save(document.edit(actor, replacement, clock.instant()), expectedVersion);
    }

    public VoucherDocument submit(UUID id, long expectedVersion, long actorId) {
        User actor = requireActor(actorId, Permission.SUBMIT_VOUCHER);
        VoucherDocument document = loadExpected(id, expectedVersion);
        document.requireActionAllowed(actor, VoucherAction.SUBMITTED);
        validator.validate(document.getContent());
        return save(document.submit(actor, clock.instant()), expectedVersion);
    }

    public VoucherDocument returnForRevision(UUID id, long expectedVersion, long actorId, String reason) {
        User actor = requireActor(actorId, Permission.REVIEW_VOUCHER);
        VoucherDocument document = loadExpected(id, expectedVersion);
        return save(document.returnForRevision(actor, reason, clock.instant()), expectedVersion);
    }

    public VoucherDocument approve(UUID id, long expectedVersion, long actorId) {
        User actor = requireActor(actorId, Permission.REVIEW_VOUCHER);
        VoucherDocument document = loadExpected(id, expectedVersion);
        document.requireActionAllowed(actor, VoucherAction.APPROVED);
        validator.validate(document.getContent());
        return save(document.approve(actor, clock.instant()), expectedVersion);
    }

    public VoucherDocument withdrawApproval(UUID id, long expectedVersion, long actorId, String reason) {
        User actor = requireActor(actorId, Permission.WITHDRAW_APPROVAL);
        VoucherDocument document = loadExpected(id, expectedVersion);
        return save(document.withdrawApproval(actor, reason, clock.instant()), expectedVersion);
    }

    public VoucherDocument cancel(UUID id, long expectedVersion, long actorId, String reason) {
        User actor = requireActor(actorId, Permission.CANCEL_VOUCHER);
        VoucherDocument document = loadExpected(id, expectedVersion);
        return save(document.cancel(actor, reason, clock.instant()), expectedVersion);
    }

    private User requireActor(long actorId, Permission permission) {
        User actor = users.findById(actorId).orElseThrow(
                () -> new AccessDeniedException("Unknown user: " + actorId));
        actor.requirePermission(permission);
        return actor;
    }

    private VoucherDocument load(UUID id) {
        return documents.findById(Objects.requireNonNull(id, "Document id is required")).orElseThrow(
                () -> new IllegalArgumentException("Voucher document not found: " + id));
    }

    private VoucherDocument loadExpected(UUID id, long expectedVersion) {
        if (expectedVersion < 0) {
            throw new IllegalArgumentException("Expected version cannot be negative");
        }
        VoucherDocument document = load(id);
        if (document.getVersion() != expectedVersion) {
            throw new StaleVersionException(id, expectedVersion, document.getVersion());
        }
        return document;
    }

    private VoucherDocument save(VoucherDocument document, long expectedVersion) {
        documents.update(document, expectedVersion);
        return document;
    }
}
