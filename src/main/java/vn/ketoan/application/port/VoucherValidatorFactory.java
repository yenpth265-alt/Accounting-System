package vn.ketoan.application.port;

@FunctionalInterface
public interface VoucherValidatorFactory {

    VoucherValidator create(AccountingSession session);
}
