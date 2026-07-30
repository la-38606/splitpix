package com.luiz.splitpix.expense;

import java.util.List;

/** {@code created} is false when an idempotent replay returned the existing expense. */
public record CreateExpenseResult(Expense expense, List<ExpenseShare> shares, boolean created) {
}
