package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.domain.JournalEntry;

public interface JournalRepository {

    Optional<JournalEntry> findById(UUID id);

    Optional<JournalEntry> findBySourceDocumentId(UUID documentId);

    List<JournalEntry> findAll();

    void append(JournalEntry entry);
}
