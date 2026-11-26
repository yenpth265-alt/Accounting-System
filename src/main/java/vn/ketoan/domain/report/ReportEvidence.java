package vn.ketoan.domain.report;

import java.util.List;
import vn.ketoan.domain.CorrectionRecord;
import vn.ketoan.domain.PrepaidAllocationRecord;
import vn.ketoan.domain.PrepaidExpensePlan;
import vn.ketoan.domain.PrepaidPlanCancellation;
import vn.ketoan.domain.VoucherDocument;

public record ReportEvidence(List<VoucherDocument> documents, List<CorrectionRecord> corrections,
                             List<PrepaidExpensePlan> prepaidPlans, List<PrepaidPlanCancellation> prepaidCancellations,
                             List<PrepaidAllocationRecord> prepaidAllocations, List<CashFlowClassification> cashClassifications) {
    public ReportEvidence {
        documents = List.copyOf(documents);
        corrections = List.copyOf(corrections);
        prepaidPlans = List.copyOf(prepaidPlans);
        prepaidCancellations = List.copyOf(prepaidCancellations);
        prepaidAllocations = List.copyOf(prepaidAllocations);
        cashClassifications = List.copyOf(cashClassifications);
    }
    public static ReportEvidence empty() { return new ReportEvidence(List.of(), List.of(), List.of(), List.of(), List.of(), List.of()); }
}
