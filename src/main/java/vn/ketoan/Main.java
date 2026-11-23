package vn.ketoan;

import vn.ketoan.demo.MasterDataDemo;
import vn.ketoan.demo.PostingDemo;
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
        throw new IllegalArgumentException(
                "Supported options: --help, --demo=master-data, --demo=vouchers, --demo=workflow, --demo=posting");
    }
}
