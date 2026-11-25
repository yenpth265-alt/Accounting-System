package vn.ketoan.application.port;

import java.util.function.Function;

/**
 * Commits the complete callback or discards all changes on failure.
 * Implementations must isolate conflicting read-modify-write operations, such as debt allocation.
 */
public interface UnitOfWork {

    <T> T execute(Function<AccountingSession, T> work);
}
