package vn.ketoan.cli;

import java.io.PrintStream;
import java.time.Clock;

import vn.ketoan.application.model.AccountingSnapshot;
import vn.ketoan.application.port.SqlConnectionFactory;
import vn.ketoan.demo.JdbcDemo;
import vn.ketoan.demo.CorrectionDemo;
import vn.ketoan.demo.PrepaidDemo;
import vn.ketoan.infrastructure.jdbc.JdbcUnitOfWork;
import vn.ketoan.infrastructure.jdbc.SchemaMigrator;

public final class DatabaseCommands {

    private DatabaseCommands() {
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
            }
            case "--demo=jdbc" -> JdbcDemo.run(output, new JdbcUnitOfWork(connections), Clock.systemUTC());
            case "--demo=corrections" -> CorrectionDemo.run(output, new JdbcUnitOfWork(connections), Clock.systemUTC());
            case "--demo=prepaid" -> PrepaidDemo.run(output, new JdbcUnitOfWork(connections), Clock.systemUTC());
            default -> throw new IllegalArgumentException("Unsupported database option");
        }
    }
}
