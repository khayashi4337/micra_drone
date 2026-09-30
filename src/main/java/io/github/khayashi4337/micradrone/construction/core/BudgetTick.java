package io.github.khayashi4337.micradrone.construction.core;

import java.util.List;

/** What one tick hands out: each job's placement allowance, and whether the server is in the slowed mode (04 F-2(c)). */
public record BudgetTick(List<Allowance> allowances, boolean slowed) {
    public BudgetTick {
        allowances = List.copyOf(allowances);
    }
}
