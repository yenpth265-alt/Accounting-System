package vn.ketoan.application.port;

import java.util.List;
import vn.ketoan.domain.report.CashFlowClassification;
import vn.ketoan.domain.report.CashFlowClassificationRequest;

public interface CashFlowClassificationUseCases {
    CashFlowClassification classify(CashFlowClassificationRequest request, long actorId);
    List<CashFlowClassification> history(long actorId);
}
