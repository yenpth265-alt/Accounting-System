package vn.ketoan.domain;

import java.util.List;

import vn.ketoan.domain.value.Money;

public interface ExpenseAllocationStrategy {

    List<Money> allocate(Money total, int months);
}
