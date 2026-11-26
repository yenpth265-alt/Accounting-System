package vn.ketoan.cli;

import java.io.PrintStream;
import java.time.Clock;

import vn.ketoan.application.model.AccountingSnapshot;
import vn.ketoan.application.port.SqlConnectionFactory;
import vn.ketoan.application.service.ReportingService;
import vn.ketoan.application.service.ReportVersionService;
import vn.ketoan.domain.report.SaveReportVersionRequest;
import vn.ketoan.domain.report.ReportPeriod;
import vn.ketoan.demo.JdbcDemo;
import vn.ketoan.demo.CorrectionDemo;
import vn.ketoan.demo.PrepaidDemo;
import vn.ketoan.demo.ClosingDemo;
import vn.ketoan.infrastructure.jdbc.JdbcUnitOfWork;
import vn.ketoan.infrastructure.jdbc.SchemaMigrator;

public final class DatabaseCommands {

    private DatabaseCommands() {
    }

    public static void reports(ReportPeriod period, PrintStream output, SqlConnectionFactory connections) {
        var reporting = new ReportingService(new JdbcUnitOfWork(connections));
        var report = reporting.complete(period, 4);
        ReportPrinter.statements(output, report.financials());
        ReportPrinter.journal(output, report.journal());
        ReportPrinter.cashFlow(output, report.cashFlow());
        ReportPrinter.notes(output, report.notes());
    }
    public static void saveReport(ReportPeriod period, PrintStream output, SqlConnectionFactory connections) {
        var value = new ReportVersionService(new JdbcUnitOfWork(connections), Clock.systemUTC()).save(
                new SaveReportVersionRequest(java.util.UUID.randomUUID(), period, "Accounting report " + period.from() + " to " + period.to()), 1);
        output.printf("Saved report version=%s; number=%d; periodsClosed=%s; cashFlowComplete=%s%n", value.id(), value.version(), value.periodsClosed(), value.cashFlowComplete());
    }
    public static void viewReport(java.util.UUID id, PrintStream output, SqlConnectionFactory connections) {
        var service = new ReportVersionService(new JdbcUnitOfWork(connections), Clock.systemUTC());
        var value = service.get(id, 4);
        boolean published = service.publications(4).stream().anyMatch(event -> event.versionId().equals(id));
        output.printf("Report version %d: %s; published=%s%n", value.version(), value.title(), published);
        ReportPrinter.document(output, value.document());
    }
    public static void publishReport(java.util.UUID id, PrintStream output, SqlConnectionFactory connections) {
        var value = new ReportVersionService(new JdbcUnitOfWork(connections), Clock.systemUTC()).publish(id, "Reviewed financial statements and cash flow", 2);
        output.println("Published report version=" + value.versionId());
    }

    public static void run(String option, PrintStream output, SqlConnectionFactory connections) {
        switch (option) {
            case "--db=migrate" -> output.println("Migrations applied: " + new SchemaMigrator(connections).migrate());
            case "--db=resume-migration" -> output.println("Migrations applied/resumed: " + new SchemaMigrator(connections).migrate(true));
            case "--db=seed-demo" -> {
                JdbcDemo.seedCatalogs(new JdbcUnitOfWork(connections));
                output.println("Demo catalogs seeded. Run --demo=jdbc to create and post the persistent sample.");
            }
            case "--db=check" -> {
                AccountingSnapshot state = new JdbcUnitOfWork(connections).execute(AccountingSnapshot::capture);
                output.println("Database connected; schema and accounting integrity are valid.");
                output.printf("Accounts=%d; partners=%d; users=%d; vouchers=%d; journal entries=%d; allocations=%d; cancellations=%d%n",
                        state.accounts().size(), state.partners().size(), state.users().size(), state.documents().size(),
                        state.journal().size(), state.allocations().size(), state.cancellations().size());
                output.printf("Corrections=%d; prepaid plans=%d; prepaid installments=%d%n",
                        state.corrections().size(), state.prepaidPlans().size(), state.prepaidAllocations().size());
                output.printf("Closing batches=%d; closing decisions=%d%n", state.closingBatches().size(), state.closingEvents().size());
                output.printf("Cash classifications=%d; report versions=%d; publications=%d%n", state.cashFlowClassifications().size(), state.reportVersions().size(), state.reportPublications().size());
            }
            case "--demo=jdbc" -> JdbcDemo.run(output, new JdbcUnitOfWork(connections), Clock.systemUTC());
            case "--demo=corrections" -> CorrectionDemo.run(output, new JdbcUnitOfWork(connections), Clock.systemUTC());
            case "--demo=prepaid" -> PrepaidDemo.run(output, new JdbcUnitOfWork(connections), Clock.systemUTC());
            case "--demo=closing" -> ClosingDemo.run(output, new JdbcUnitOfWork(connections), Clock.systemUTC());
            default -> throw new IllegalArgumentException("Unsupported database option");
        }
    }
}
