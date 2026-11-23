package vn.ketoan.application.port;

import java.util.function.Function;

/** Commits the complete callback or discards all changes on failure. */
public interface UnitOfWork {

    <T> T execute(Function<AccountingSession, T> work);
}
