package vn.ketoan;

import java.nio.file.Path;
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
            System.out.println("MySQL: configure database.local.properties or ACCOUNTING_DB_URL/USER/PASSWORD.");
            System.out.println("Run --db=migrate, --db=seed-demo, then --demo=jdbc for a persistent sample.");
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
        if (args.length == 1 && Set.of("--db=migrate", "--db=resume-migration", "--db=seed-demo", "--db=check", "--demo=jdbc").contains(args[0])) {
            DatabaseSettings settings = DatabaseSettings.load(Path.of("database.local.properties"), System.getenv());
            DatabaseCommands.run(args[0], System.out, new DatabaseConnection(settings));
            return;
        }
        throw new IllegalArgumentException(
                "Supported options: --help, --demo=master-data, --demo=vouchers, --demo=workflow, "
                        + "--demo=posting, --demo=recognition, --demo=settlement, --demo=jdbc, "
                        + "--db=migrate, --db=resume-migration, --db=seed-demo, --db=check");
    }
}
