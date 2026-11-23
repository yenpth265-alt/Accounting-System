package vn.ketoan;

import vn.ketoan.demo.MasterDataDemo;

public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            System.out.println("Accounting System");
            System.out.println("Run with --demo=master-data to see accounts, partners and periods.");
            return;
        }
        if (args.length == 1 && "--demo=master-data".equals(args[0])) {
            MasterDataDemo.run(System.out);
            return;
        }
        throw new IllegalArgumentException("Supported options: --help, --demo=master-data");
    }
}
