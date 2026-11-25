package vn.ketoan.domain;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import vn.ketoan.domain.value.Money;

/** Pure double-entry calculation. Contra balances and analytic partner details are preserved. */
public final class ClosingCalculator {

    public ClosingPreview calculate(ClosingRequest request, Collection<Account> accounts, Collection<JournalEntry> sources) {
        Map<String, Account> index = accounts.stream().collect(Collectors.toMap(Account::getCode, Function.identity()));
        requireAccount(index, request.resultAccountCode(), AccountType.RESULT);
        requireAccount(index, request.retainedEarningsAccountCode(), AccountType.EQUITY);
        Map<Key, Money> revenue = new TreeMap<>();
        Map<Key, Money> expense = new TreeMap<>();
        Map<Key, Money> result = new TreeMap<>();
        for (JournalEntry entry : sources) {
            if (!request.month().equals(java.time.YearMonth.from(entry.getPostingDate()))) {
                throw new IllegalArgumentException("Closing sources must belong to the requested month");
            }
            for (JournalLine line : entry.getLines()) {
                Account account = index.get(line.getAccountCode());
                if (account == null) { throw new IllegalArgumentException("Closing source account not found"); }
                Map<Key, Money> target = switch (account.getType()) {
                    case REVENUE -> revenue;
                    case EXPENSE -> expense;
                    case RESULT -> result;
                    default -> null;
                };
                if (target != null) {
                    if (account.getTracking() == AccountTracking.MONEY_ACCOUNT || line.getMoneyAccountCode().isPresent()) {
                        throw new IllegalArgumentException("Temporary accounts cannot represent cash or bank funds");
                    }
                    target.merge(new Key(account.getCode(), line.getPartnerCode().orElse("")),
                            line.getDebit().subtract(line.getCredit()), Money::add);
                }
            }
        }
        if (result.values().stream().anyMatch(amount -> !amount.isZero())) {
            throw new IllegalStateException("Resolve ordinary result-account balances before automatic closing");
        }
        Money revenueTotal = sum(revenue).negate();
        Money expenseTotal = sum(expense);
        Money profit = revenueTotal.subtract(expenseTotal);
        List<Draft> drafts = new ArrayList<>();
        transfer(request, revenue, "_REV", "Transfer revenue", drafts);
        transfer(request, expense, "_EXP", "Transfer expenses", drafts);
        if (!profit.isZero()) {
            drafts.add(draft(request, "_RES", "Transfer profit or loss", List.of(
                    line(request.resultAccountCode(), profit.isPositive() ? BalanceSide.DEBIT : BalanceSide.CREDIT, absolute(profit), null),
                    line(request.retainedEarningsAccountCode(), profit.isPositive() ? BalanceSide.CREDIT : BalanceSide.DEBIT, absolute(profit), null))));
        }
        return new ClosingPreview(revenueTotal, expenseTotal, profit, checksum(sources), drafts);
    }

    public void requireZeroTemporaryBalances(Collection<Account> accounts, Collection<JournalEntry> entries,
                                             java.time.LocalDate asOf) {
        var temporary = accounts.stream().filter(account -> account.getType() == AccountType.REVENUE
                || account.getType() == AccountType.EXPENSE || account.getType() == AccountType.RESULT)
                .map(Account::getCode).collect(Collectors.toSet());
        Map<Key, Money> balances = new TreeMap<>();
        entries.stream().filter(entry -> !entry.getPostingDate().isAfter(asOf)).flatMap(entry -> entry.getLines().stream())
                .filter(line -> temporary.contains(line.getAccountCode())).forEach(line -> balances.merge(
                        new Key(line.getAccountCode(), line.getPartnerCode().orElse("")), line.getDebit().subtract(line.getCredit()), Money::add));
        if (balances.values().stream().anyMatch(value -> !value.isZero())) {
            throw new IllegalStateException("Revenue, expense and result accounts must have zero balances at the period boundary");
        }
    }

    private void requireAccount(Map<String, Account> accounts, String code, AccountType type) {
        Account account = accounts.get(code);
        if (account == null || account.getType() != type || !account.isPostingAllowed() || account.getTracking() != AccountTracking.NONE) {
            throw new IllegalArgumentException("Closing requires an untracked posting " + type + " account: " + code);
        }
    }

    private void transfer(ClosingRequest request, Map<Key, Money> balances, String suffix, String description, List<Draft> drafts) {
        List<VoucherLine> lines = new ArrayList<>();
        balances.forEach((key, net) -> {
            if (net.isZero()) { return; }
            lines.add(line(key.account(), net.isPositive() ? BalanceSide.CREDIT : BalanceSide.DEBIT,
                    absolute(net), key.partner().isEmpty() ? null : key.partner()));
            lines.add(line(request.resultAccountCode(), net.isPositive() ? BalanceSide.DEBIT : BalanceSide.CREDIT, absolute(net), null));
        });
        if (!lines.isEmpty()) { drafts.add(draft(request, suffix, description, lines)); }
    }

    private Draft draft(ClosingRequest request, String suffix, String description, List<VoucherLine> lines) {
        var date = request.month().atEndOfMonth();
        return new Draft(request.reference() + suffix, date, date, description + " " + request.month(), lines);
    }
    private VoucherLine line(String code, BalanceSide side, Money amount, String partner) { return new VoucherLine(code, side, amount, partner, null); }
    private Money absolute(Money amount) { return amount.isNegative() ? amount.negate() : amount; }
    private Money sum(Map<Key, Money> amounts) { return amounts.values().stream().reduce(Money.ZERO, Money::add); }

    private String checksum(Collection<JournalEntry> sources) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            sources.stream().sorted(Comparator.comparing(entry -> entry.getId().toString())).forEach(entry -> {
                token(digest, entry.getId().toString());
                token(digest, entry.getSourceDocumentId().toString());
                token(digest, Long.toString(entry.getSourceVersion()));
                var content = entry.getContent();
                for (String value : List.of(content.type().name(), content.reference(), content.documentDate().toString(),
                        content.postingDate().toString(), content.description(), Integer.toString(content.lines().size()))) { token(digest, value); }
                for (JournalLine line : entry.getLines()) {
                    for (String value : List.of(Integer.toString(line.getLineNumber()), line.getAccountCode(), line.getSide().name(),
                            line.getAmount().toString(), line.getPartnerCode().orElse(""), line.getMoneyAccountCode().orElse(""))) { token(digest, value); }
                }
            });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
    private void token(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }
    private record Key(String account, String partner) implements Comparable<Key> {
        @Override public int compareTo(Key other) {
            int comparison = account.compareTo(other.account);
            return comparison == 0 ? partner.compareTo(other.partner) : comparison;
        }
    }
}
