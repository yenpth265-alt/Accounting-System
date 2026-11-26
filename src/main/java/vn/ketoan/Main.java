package vn.ketoan;

import java.nio.file.Path;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Set;

import vn.ketoan.cli.DatabaseCommands;
import vn.ketoan.config.DatabaseConnection;
import vn.ketoan.config.DatabaseSettings;
import vn.ketoan.demo.MasterDataDemo;
import vn.ketoan.demo.PostingDemo;
import vn.ketoan.demo.RecognitionDemo;
import vn.ketoan.demo.SettlementDemo;
import vn.ketoan.demo.VoucherDemo;
import vn.ketoan.demo.WorkflowDemo;
import vn.ketoan.demo.ReportDemo;
import vn.ketoan.demo.ReportArchiveDemo;
import vn.ketoan.domain.report.ReportPeriod;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            System.out.println("Accounting System");
            System.out.println("Run with --demo=master-data to see accounts, partners and periods.");
            System.out.println("Run with --demo=vouchers to see voucher creation and validation patterns.");
            System.out.println("Run with --demo=workflow to see permissions, approvals and audit history.");
            System.out.println("Run with --demo=posting to see atomic journal posting and ledger balances.");
            System.out.println("Run with --demo=recognition to enter revenue and expenses independently of cash.");
            System.out.println("Run with --demo=settlement to collect/pay debts, allocate amounts and inspect open items.");
            System.out.println("Run with --demo=reports for in-memory journal, ledger and financial reports before/after closing.");
            System.out.println("Run with --demo=report-versions for in-memory classified cash flows, notes and frozen report versions.");
            System.out.println("MySQL: configure database.local.properties or ACCOUNTING_DB_URL/USER/PASSWORD.");
            System.out.println("Run --db=migrate, --db=seed-demo, then --demo=jdbc for a persistent sample.");
            System.out.println("Run --demo=corrections or --demo=prepaid for persistent adjustments, reversals and prepaid expenses.");
            System.out.println("Run --demo=closing to prepare, review, post and lock the November 2026 demo period in MySQL.");
            System.out.println("Run --reports=YYYY-MM to read complete monthly reports from MySQL (requires schema V005).");
            System.out.println("Run --report-save=YYYY-MM to save a draft version; --report-view=UUID to read it; --report-publish=UUID to record independent approval.");
            System.out.println("Console report actions use demo actors: preparer 1, reviewer 2, reader 4.");
            System.out.println("Run --db=check to inspect stored data; --db=resume-migration resumes an inspected failed migration.");
            return;
        }
        if (args.length == 1 && "--demo=master-data".equals(args[0])) {
            MasterDataDemo.run(System.out);
            return;
        }
        if (args.length == 1 && "--demo=vouchers".equals(args[0])) {
            VoucherDemo.run(System.out);
            return;
        }
        if (args.length == 1 && "--demo=workflow".equals(args[0])) {
            WorkflowDemo.run(System.out);
            return;
        }
        if (args.length == 1 && "--demo=posting".equals(args[0])) {
            PostingDemo.run(System.out);
            return;
        }
        if (args.length == 1 && "--demo=recognition".equals(args[0])) {
            RecognitionDemo.run(System.out);
            return;
        }
        if (args.length == 1 && "--demo=settlement".equals(args[0])) {
            SettlementDemo.run(System.out);
            return;
        }
        if (args.length == 1 && "--demo=reports".equals(args[0])) {
            ReportDemo.run(System.out);
            return;
        }
        if (args.length == 1 && "--demo=report-versions".equals(args[0])) {
            ReportArchiveDemo.run(System.out);
            return;
        }
        if (args.length == 1 && (args[0].startsWith("--reports=") || args[0].startsWith("--report-save="))) {
            ReportPeriod period;
            try { period = ReportPeriod.month(YearMonth.parse(args[0].substring(args[0].indexOf('=') + 1))); }
            catch (DateTimeParseException invalid) { throw new IllegalArgumentException("Use --reports=YYYY-MM or --report-save=YYYY-MM", invalid); }
            DatabaseSettings settings = DatabaseSettings.load(Path.of("database.local.properties"), System.getenv());
            if (args[0].startsWith("--report-save=")) { DatabaseCommands.saveReport(period, System.out, new DatabaseConnection(settings)); }
            else { DatabaseCommands.reports(period, System.out, new DatabaseConnection(settings)); }
            return;
        }
        if (args.length == 1 && (args[0].startsWith("--report-view=") || args[0].startsWith("--report-publish="))) {
            java.util.UUID id = java.util.UUID.fromString(args[0].substring(args[0].indexOf('=') + 1));
            DatabaseSettings settings = DatabaseSettings.load(Path.of("database.local.properties"), System.getenv());
            if (args[0].startsWith("--report-view=")) { DatabaseCommands.viewReport(id, System.out, new DatabaseConnection(settings)); }
            else { DatabaseCommands.publishReport(id, System.out, new DatabaseConnection(settings)); }
            return;
        }
        if (args.length == 1 && Set.of("--db=migrate", "--db=resume-migration", "--db=seed-demo", "--db=check", "--demo=jdbc",
                "--demo=corrections", "--demo=prepaid", "--demo=closing").contains(args[0])) {
            DatabaseSettings settings = DatabaseSettings.load(Path.of("database.local.properties"), System.getenv());
            DatabaseCommands.run(args[0], System.out, new DatabaseConnection(settings));
            return;
        }
        throw new IllegalArgumentException(
                "Supported options: --help, --demo=master-data, --demo=vouchers, --demo=workflow, "
                        + "--demo=posting, --demo=recognition, --demo=settlement, --demo=reports, --demo=report-versions, --reports=YYYY-MM, --report-save=YYYY-MM, --report-view=UUID, --report-publish=UUID, --demo=jdbc, --demo=corrections, --demo=prepaid, --demo=closing, "
                        + "--db=migrate, --db=resume-migration, --db=seed-demo, --db=check");
    }
}
