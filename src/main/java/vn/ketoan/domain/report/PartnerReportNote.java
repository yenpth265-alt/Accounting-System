package vn.ketoan.domain.report;

public record PartnerReportNote(String accountCode, String accountName, String partnerCode, String partnerName,
                                DebitCredit opening, DebitCredit movements, DebitCredit closing) {
    public PartnerReportNote {
        accountCode = vn.ketoan.domain.Account.normalizeCode(accountCode);
        partnerCode = vn.ketoan.domain.Partner.normalizeCode(partnerCode);
        accountName = ReportText.required(accountName, 120, "Account name");
        partnerName = ReportText.required(partnerName, 160, "Partner name");
        if (!opening.net().add(movements.net()).equals(closing.net())) { throw new IllegalArgumentException("Partner note does not reconcile"); }
    }
}
