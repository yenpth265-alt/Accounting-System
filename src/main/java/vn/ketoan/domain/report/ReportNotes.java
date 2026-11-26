package vn.ketoan.domain.report;

import java.util.List;

public record ReportNotes(ReportPeriod period, List<String> disclosures, List<FundReportNote> funds,
                           List<PartnerReportNote> partners, List<PrepaidReportNote> prepaid,
                           List<CorrectionReportNote> corrections, List<CashFlowClassification> classifications) {
    public ReportNotes {
        java.util.Objects.requireNonNull(period);
        disclosures = List.copyOf(disclosures);
        funds = List.copyOf(funds);
        partners = List.copyOf(partners);
        prepaid = List.copyOf(prepaid);
        corrections = List.copyOf(corrections);
        classifications = List.copyOf(classifications);
    }
}
