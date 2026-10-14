package cn.panda.pandagpt.performance;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TickBudgetTest {
    @Test void sharesQuotaAndChecksDeadlineBeforeAWorkUnit() {
        long[] time = {100}; var budget = new TickBudget(20, 2, true, () -> time[0]);
        assertTrue(budget.cell()); assertTrue(budget.cell()); assertFalse(budget.cell()); assertEquals(2, budget.cells());
        var deadline = new TickBudget(20, 100, true, () -> time[0]); time[0] = 120;
        assertFalse(deadline.cell()); assertEquals(0, deadline.cells());
    }
    @Test void pressureHasHysteresisAndReducesForegroundBudget() {
        var governor = new LoadGovernor(); governor.observe(45);
        var budget = governor.budget(2000000, 8192); assertFalse(budget.background());
        assertTrue(budget.available());
        for (int i = 0; i < 4; i++) governor.observe(34);
        assertFalse(governor.budget(2000000, 8192).background());
        governor.observe(40); // Recovery must be consecutive.
        for (int i = 0; i < 4; i++) governor.observe(34);
        assertFalse(governor.budget(2000000, 8192).background());
        governor.observe(34); assertTrue(governor.budget(2000000, 8192).background());
        governor.observe(Double.NaN); assertTrue(governor.budget(2000000, 8192).background());
    }
}
