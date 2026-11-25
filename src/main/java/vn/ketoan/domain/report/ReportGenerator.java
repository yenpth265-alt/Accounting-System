package vn.ketoan.domain.report;

/** Interchangeable pure report calculations; neither storage nor UI belongs here. */
@FunctionalInterface
public interface ReportGenerator<Q, R> { R generate(ReportContext context, Q query); }
