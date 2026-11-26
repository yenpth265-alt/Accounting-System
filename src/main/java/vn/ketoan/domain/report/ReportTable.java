package vn.ketoan.domain.report;

import java.util.List;

/** UI-independent frozen table. Decimal amounts remain exact whole-dong strings. */
public record ReportTable(String code, String title, List<String> columns, List<List<String>> rows) {
    public ReportTable {
        code = ReportText.required(code, 32, "Table code");
        if (!code.matches("[A-Z][A-Z0-9_]*")) { throw new IllegalArgumentException("Invalid report table code"); }
        title = text(ReportText.required(title, 160, "Table title"));
        columns = columns.stream().map(value -> text(ReportText.required(value, 160, "Column title"))).toList();
        rows = rows.stream().map(row -> row.stream().map(ReportTable::text).toList()).toList();
        if (columns.isEmpty() || columns.size() > 32 || rows.size() > 50_000) { throw new IllegalArgumentException("Report table is too large or empty"); }
        for (List<String> row : rows) { if (row.size() != columns.size()) { throw new IllegalArgumentException("Report table cells must match its columns"); } }
    }
    private static String text(String value) {
        String text = java.util.Objects.requireNonNull(value).replace("\r\n", "\n").replace('\r', '\n');
        if (text.length() > 65_536 || text.codePoints().anyMatch(code -> !(code == 9 || code == 10 || (code >= 32 && code <= 0xD7FF)
                || (code >= 0xE000 && code <= 0xFFFD) || (code >= 0x10000 && code <= 0x10FFFF)))) {
            throw new IllegalArgumentException("Report text contains unsupported characters or is too large");
        }
        return text;
    }
}
