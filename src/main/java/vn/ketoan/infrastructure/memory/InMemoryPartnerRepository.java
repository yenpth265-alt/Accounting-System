package vn.ketoan.infrastructure.memory;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.domain.Partner;

/** Single-process demo storage. Cross-entity transactions are not provided here. */
public final class InMemoryPartnerRepository implements PartnerRepository {

    private final Map<String, Partner> partners = new TreeMap<>();

    @Override
    public Optional<Partner> findByCode(String code) {
        return Optional.ofNullable(partners.get(Partner.normalizeCode(code)));
    }

    @Override
    public List<Partner> findAll() {
        return List.copyOf(partners.values());
    }

    @Override
    public void insert(Partner partner) {
        Objects.requireNonNull(partner, "Partner is required");
        if (partners.putIfAbsent(partner.getCode(), partner) != null) {
            throw new IllegalStateException("Partner already exists: " + partner.getCode());
        }
    }

    @Override
    public void update(Partner partner) {
        Objects.requireNonNull(partner, "Partner is required");
        if (partners.replace(partner.getCode(), partner) == null) {
            throw new IllegalArgumentException("Partner not found: " + partner.getCode());
        }
    }
}
