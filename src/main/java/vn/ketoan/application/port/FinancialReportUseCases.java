package vn.ketoan.application.port;

import java.time.LocalDate;
import vn.ketoan.domain.report.BalanceSheet;
import vn.ketoan.domain.report.FinancialStatements;
import vn.ketoan.domain.report.IncomeStatement;
import vn.ketoan.domain.report.ReportPeriod;

public interface FinancialReportUseCases {
    IncomeStatement incomeStatement(ReportPeriod period, long actorId);
    BalanceSheet balanceSheet(LocalDate asOf, long actorId);
    FinancialStatements statements(ReportPeriod period, long actorId);
}
