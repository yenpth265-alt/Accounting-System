package vn.ketoan.application.port;

import vn.ketoan.domain.report.ReportDocument;

public interface ReportDocumentCodec {
    String encode(ReportDocument document);
    ReportDocument decode(String payload);
}
