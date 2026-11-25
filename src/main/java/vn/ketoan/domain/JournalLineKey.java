package vn.ketoan.domain;

import java.util.Objects;
import java.util.UUID;

/** Stable identity of an immutable posted journal line. */
public record JournalLineKey(UUID journalEntryId, int lineNumber) {

    public JournalLineKey {
        Objects.requireNonNull(journalEntryId, "Journal entry id is required");
        if (lineNumber < 1) {
            throw new IllegalArgumentException("Journal line numbers start at one");
        }
    }
}
