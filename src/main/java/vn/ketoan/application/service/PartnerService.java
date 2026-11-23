package vn.ketoan.application.service;

import java.util.List;
import java.util.Objects;

import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;

public final class PartnerService {

    private final PartnerRepository partners;

    public PartnerService(PartnerRepository partners) {
        this.partners = Objects.requireNonNull(partners, "Partner repository is required");
    }

    public void add(Partner partner) {
        partners.insert(Objects.requireNonNull(partner, "Partner is required"));
    }

    public Partner get(String code) {
        String normalized = Partner.normalizeCode(code);
        return partners.findByCode(normalized).orElseThrow(
                () -> new IllegalArgumentException("Partner not found: " + normalized));
    }

    public List<Partner> list() {
        return partners.findAll();
    }

    public Partner requireRole(String code, PartnerRole role) {
        Partner partner = get(code);
        partner.requireRole(role);
        return partner;
    }

    public void rename(String code, String newName) {
        partners.update(get(code).rename(newName));
    }

    public void addRole(String code, PartnerRole role) {
        partners.update(get(code).addRole(role));
    }

    public void deactivate(String code) {
        partners.update(get(code).deactivate());
    }

    public void activate(String code) {
        partners.update(get(code).activate());
    }
}
