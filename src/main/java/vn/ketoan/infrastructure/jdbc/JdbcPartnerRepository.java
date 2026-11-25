package vn.ketoan.infrastructure.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import vn.ketoan.application.port.PartnerRepository;
import vn.ketoan.domain.Partner;
import vn.ketoan.domain.PartnerRole;

final class JdbcPartnerRepository implements PartnerRepository {

    private final JdbcContext context;

    JdbcPartnerRepository(JdbcContext context) {
        this.context = context;
    }

    @Override
    public Optional<Partner> findByCode(String code) {
        return context.guard(() -> context.one("SELECT * FROM acc_partners WHERE code=?", this::read, Partner.normalizeCode(code)));
    }

    @Override
    public List<Partner> findAll() {
        return context.query("SELECT * FROM acc_partners ORDER BY code", this::read);
    }

    @Override
    public void insert(Partner value) {
        context.mutate(() -> context.update("INSERT INTO acc_partners(code,name,is_customer,is_supplier,active) VALUES (?,?,?,?,?)",
                value.getCode(), value.getName(), value.getRoles().contains(PartnerRole.CUSTOMER),
                value.getRoles().contains(PartnerRole.SUPPLIER), value.isActive()));
    }

    @Override
    public void update(Partner value) {
        context.mutate(() -> {
            findByCode(value.getCode()).orElseThrow(() -> new IllegalArgumentException("Partner not found: " + value.getCode()));
            context.update("UPDATE acc_partners SET name=?,is_customer=?,is_supplier=?,active=? WHERE code=?",
                    value.getName(), value.getRoles().contains(PartnerRole.CUSTOMER), value.getRoles().contains(PartnerRole.SUPPLIER),
                    value.isActive(), value.getCode());
        });
    }

    private Partner read(ResultSet row) throws SQLException {
        Set<PartnerRole> roles = EnumSet.noneOf(PartnerRole.class);
        if (row.getBoolean("is_customer")) { roles.add(PartnerRole.CUSTOMER); }
        if (row.getBoolean("is_supplier")) { roles.add(PartnerRole.SUPPLIER); }
        Partner partner = new Partner(row.getString("code"), row.getString("name"), roles);
        return row.getBoolean("active") ? partner : partner.deactivate();
    }
}
