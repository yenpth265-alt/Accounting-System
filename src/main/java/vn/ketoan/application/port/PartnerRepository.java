package vn.ketoan.application.port;

import java.util.List;
import java.util.Optional;

import vn.ketoan.domain.Partner;

public interface PartnerRepository {

    Optional<Partner> findByCode(String code);

    List<Partner> findAll();

    void insert(Partner partner);

    void update(Partner partner);
}
