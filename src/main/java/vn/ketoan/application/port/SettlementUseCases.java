package vn.ketoan.application.port;

import java.util.UUID;

import vn.ketoan.domain.SettlementDraft;
import vn.ketoan.domain.VoucherDocument;

public interface SettlementUseCases {

    VoucherDocument createCustomerReceipt(SettlementDraft input, long actorId);

    VoucherDocument createSupplierPayment(SettlementDraft input, long actorId);

    VoucherDocument editCustomerReceipt(UUID id, long version, SettlementDraft input, long actorId);

    VoucherDocument editSupplierPayment(UUID id, long version, SettlementDraft input, long actorId);
}
