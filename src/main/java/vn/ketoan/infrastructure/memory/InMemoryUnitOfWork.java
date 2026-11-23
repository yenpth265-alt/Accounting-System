package vn.ketoan.infrastructure.memory;

import java.util.Objects;
import java.util.function.Function;

import vn.ketoan.application.port.AccountingSession;
import vn.ketoan.application.port.UnitOfWork;

/** Serial transactions with copy-on-write publication; intended for small demos/tests. */
public final class InMemoryUnitOfWork implements UnitOfWork {

    private InMemoryAccountingState committed = InMemoryAccountingState.empty();
    private InMemoryAccountingSession current;

    @Override
    public synchronized <T> T execute(Function<AccountingSession, T> work) {
        Objects.requireNonNull(work, "Transaction callback is required");
        if (current != null) {
            current.abort();
            throw new IllegalStateException("Nested transactions are not supported");
        }
        InMemoryAccountingSession session = new InMemoryAccountingSession(committed);
        current = session;
        try {
            T result = work.apply(session);
            InMemoryAccountingState next = session.snapshot();
            next.validateIntegrity();
            committed = next;
            return result;
        } finally {
            session.close();
            current = null;
        }
    }
}
