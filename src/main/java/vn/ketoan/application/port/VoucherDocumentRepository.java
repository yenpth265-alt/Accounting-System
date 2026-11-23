package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import vn.ketoan.domain.VoucherDocument;

/** Stores a document and its history atomically; update requires the expected version. */
public interface VoucherDocumentRepository {

    Optional<VoucherDocument> findById(UUID id);

    List<VoucherDocument> findAll();

    void insert(VoucherDocument document);

    void update(VoucherDocument document, long expectedVersion);
}
