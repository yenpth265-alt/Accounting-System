package vn.ketoan.infrastructure.memory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.application.port.JournalRepository;
import vn.ketoan.domain.JournalEntry;

public final class InMemoryJournalRepository implements JournalRepository {

    private final Map<UUID, JournalEntry> entries = new LinkedHashMap<>();
    private final Map<UUID, UUID> sources = new LinkedHashMap<>();

    @Override
    public synchronized Optional<JournalEntry> findById(UUID id) {
        return Optional.ofNullable(entries.get(Objects.requireNonNull(id, "Journal id is required")));
    }

    @Override
    public synchronized Optional<JournalEntry> findBySourceDocumentId(UUID documentId) {
        Objects.requireNonNull(documentId, "Source document id is required");
        return Optional.ofNullable(sources.get(documentId)).map(entries::get);
    }

    @Override
    public synchronized List<JournalEntry> findAll() {
        return List.copyOf(entries.values());
    }

    @Override
    public synchronized void append(JournalEntry entry) {
        Objects.requireNonNull(entry, "Journal entry is required");
        if (entries.containsKey(entry.getId()) || sources.containsKey(entry.getSourceDocumentId())) {
            throw new IllegalStateException("Journal id or source document is already posted");
        }
        entries.put(entry.getId(), entry);
        sources.put(entry.getSourceDocumentId(), entry.getId());
    }
}
