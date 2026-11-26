package vn.ketoan.domain.report;

public enum CashFlowDirection {
    INFLOW, OUTFLOW;
    public CashFlowDirection opposite() { return this == INFLOW ? OUTFLOW : INFLOW; }
}
