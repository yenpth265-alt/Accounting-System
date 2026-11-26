package vn.ketoan.domain.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public final class TabularReportDocumentBuilder implements ReportDocumentBuilder {
    @Override public ReportDocument build(ReportPackage report) {
        var tables = new ArrayList<ReportTable>();
        tables.add(table("TRIAL_BALANCE", "Trial balance", List.of("Account", "Name", "Opening Dr", "Opening Cr", "Movement Dr", "Movement Cr", "Closing Dr", "Closing Cr"),
                report.financials().trialBalance().rows().stream().map(row -> cells(row.accountCode(), row.accountName(), row.opening().debit(), row.opening().credit(), row.movements().debit(), row.movements().credit(), row.closing().debit(), row.closing().credit())).toList()));
        var income = new ArrayList<List<String>>();
        report.financials().incomeStatement().rows().forEach(row -> income.add(cells(row.type(), row.accountCode(), row.accountName(), row.amount())));
        income.add(cells("TOTAL", "", "Revenue", report.financials().incomeStatement().revenue()));
        income.add(cells("TOTAL", "", "Expenses", report.financials().incomeStatement().expense()));
        income.add(cells("TOTAL", "", "Profit/loss", report.financials().incomeStatement().profit()));
        tables.add(table("INCOME", "Income statement", List.of("Type", "Account", "Name", "Amount"), income));
        var position = new ArrayList<List<String>>();
        report.financials().balanceSheet().rows().forEach(row -> position.add(cells(row.section(), row.accountCode(), row.partnerCode(), row.accountName(), row.amount(), row.reclassified())));
        position.add(cells("TOTAL", "", "", "Assets", report.financials().balanceSheet().assets(), ""));
        position.add(cells("TOTAL", "", "", "Liabilities", report.financials().balanceSheet().liabilities(), ""));
        position.add(cells("TOTAL", "", "", "Equity", report.financials().balanceSheet().equity(), ""));
        tables.add(table("BALANCE_SHEET", "Balance sheet", List.of("Section", "Account", "Partner", "Name", "Amount", "Reclassified"), position));
        var cash = new ArrayList<List<String>>();
        cash.add(cells("OPENING", "", "", "", report.cashFlow().openingCash(), ""));
        report.cashFlow().rows().forEach(row -> cash.add(cells(row.reference(), row.postingDate(), row.activity(), row.direction(), row.amount(), row.inheritedReversal())));
        for (var activity : CashFlowActivity.values()) { cash.add(cells("NET", "", activity, "NET", report.cashFlow().net(activity), "")); }
        cash.add(cells("CLOSING", "", "", "", report.cashFlow().closingCash(), ""));
        cash.add(cells("CLASSIFIED", "", "", "", report.cashFlow().complete(), ""));
        tables.add(table("CASH_FLOW", "Direct cash flow statement", List.of("Entry", "Date", "Activity", "Direction", "Amount", "Reversal"), cash));
        tables.add(table("INTERNAL_TRANSFERS", "Internal transfers excluded from external flows", List.of("Entry", "Date", "Amount"),
                report.cashFlow().internalTransfers().stream().map(row -> cells(row.reference(), row.postingDate(), row.amount())).toList()));
        tables.add(table("FUNDS", "Cash and bank reconciliation", List.of("Fund", "Name", "Account", "Opening Dr", "Opening Cr", "In", "Out", "Closing Dr", "Closing Cr"),
                report.notes().funds().stream().map(row -> cells(row.code(), row.name(), row.accountCode(), row.opening().debit(), row.opening().credit(), row.movements().debit(), row.movements().credit(), row.closing().debit(), row.closing().credit())).toList()));
        tables.add(table("PARTNERS", "Counterparty balance reconciliation", List.of("Account", "Partner", "Name", "Opening Dr", "Opening Cr", "Movement Dr", "Movement Cr", "Closing Dr", "Closing Cr"),
                report.notes().partners().stream().map(row -> cells(row.accountCode(), row.partnerCode(), row.partnerName(), row.opening().debit(), row.opening().credit(), row.movements().debit(), row.movements().credit(), row.closing().debit(), row.closing().credit())).toList()));
        tables.add(table("PREPAID", "Prepaid expense schedules", List.of("Plan", "Original", "Recognized in period", "Recognized total", "Remaining asset", "Cancelled"),
                report.notes().prepaid().stream().map(row -> cells(row.reference(), row.original(), row.recognizedInPeriod(), row.totalRecognized(), row.remainingAsset(), row.cancelled())).toList()));
        tables.add(table("CORRECTIONS", "Posted adjustments and reversals", List.of("Source", "Target", "Kind", "Date", "Reason"),
                report.notes().corrections().stream().map(row -> cells(row.sourceReference(), row.targetReference(), row.kind(), row.postingDate(), row.reason())).toList()));
        Map<UUID, String> references = report.sourceRows().stream().collect(Collectors.toMap(JournalRow::entryId, JournalRow::reference, (left, right) -> left));
        var classified = new ArrayList<List<String>>();
        for (var value : report.notes().classifications()) {
            if (value.splits().isEmpty()) { classified.add(cells(references.get(value.entryId()), value.revision(), value.reason(), value.internalTransfer(), "", "", "", value.recordedBy(), value.recordedAt())); }
            for (var split : value.splits()) { classified.add(cells(references.get(value.entryId()), value.revision(), value.reason(), value.internalTransfer(), split.activity(), split.direction(), split.amount(), value.recordedBy(), value.recordedAt())); }
        }
        tables.add(table("CLASSIFICATIONS", "Cash flow classification evidence", List.of("Entry", "Revision", "Reason", "Internal transfer", "Activity", "Direction", "Amount", "Actor", "Recorded at"), classified));
        var disclosures = new ArrayList<List<String>>();
        report.notes().disclosures().forEach(value -> disclosures.add(cells(value)));
        disclosures.add(cells("Source periods closed: " + report.financials().trialBalance().periodsClosed()));
        tables.add(table("DISCLOSURES", "Basis and explanatory disclosures", List.of("Note"), disclosures));
        tables.add(table("JOURNAL", "General journal", List.of("Date", "Reference", "Line", "Account", "Partner", "Fund", "Dr", "Cr", "Closing"),
                report.journal().rows().stream().map(row -> cells(row.postingDate(), row.reference(), row.lineNumber(), row.accountCode(), row.partnerCode(), row.moneyAccountCode(), row.amounts().debit(), row.amounts().credit(), row.closing())).toList()));
        return new ReportDocument(report.period(), tables);
    }
    private ReportTable table(String code, String title, List<String> columns, List<List<String>> rows) { return new ReportTable(code, title, columns, rows); }
    private List<String> cells(Object... values) { return java.util.Arrays.stream(values).map(value -> value == null ? "" : value.toString()).toList(); }
}
