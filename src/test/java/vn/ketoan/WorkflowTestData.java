package vn.ketoan;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import vn.ketoan.application.service.VoucherWorkflowService;
import vn.ketoan.domain.Draft;
import vn.ketoan.domain.ReceiptCreator;
import vn.ketoan.domain.User;
import vn.ketoan.domain.UserRole;
import vn.ketoan.domain.Voucher;
import vn.ketoan.infrastructure.memory.InMemoryUserRepository;
import vn.ketoan.infrastructure.memory.InMemoryVoucherDocumentRepository;

public final class WorkflowTestData {

    public static final long OWNER = 1;
    public static final long CHIEF = 2;
    public static final long OTHER = 3;
    public static final long VIEWER = 4;
    public static final long ADMIN = 5;
    public static final long COMBINED = 6;
    public static final Instant TIME = Instant.parse("2026-11-24T03:00:00Z");

    private WorkflowTestData() {
    }

    public static User user(long id, UserRole... roles) {
        return new User(id, "user" + id, "User " + id, Set.of(roles));
    }

    public static Draft receiptDraft(String reference, long amount) {
        return new Draft(reference, VoucherTestData.DATE, VoucherTestData.DATE, "Receipt draft",
                List.of(VoucherTestData.debit("1111", amount, null, "CASH01"),
                        VoucherTestData.credit("131", amount, "KH01", null)));
    }

    public static Voucher content() {
        return new ReceiptCreator().create(receiptDraft("WF001", 4_000_000));
    }

    public static Environment environment() {
        InMemoryUserRepository users = new InMemoryUserRepository();
        users.insert(user(OWNER, UserRole.ACCOUNTANT));
        users.insert(user(CHIEF, UserRole.CHIEF));
        users.insert(user(OTHER, UserRole.ACCOUNTANT));
        users.insert(user(VIEWER, UserRole.VIEWER));
        users.insert(user(ADMIN, UserRole.ADMIN));
        users.insert(user(COMBINED, UserRole.ACCOUNTANT, UserRole.CHIEF));
        return new Environment(VoucherTestData.stores(), users, new InMemoryVoucherDocumentRepository());
    }

    public record Environment(VoucherTestData.Stores catalogs, InMemoryUserRepository users,
                              InMemoryVoucherDocumentRepository documents) {

        public VoucherWorkflowService service() {
            return new VoucherWorkflowService(users, documents, catalogs.validator(),
                    Clock.fixed(TIME, ZoneOffset.UTC));
        }
    }
}
