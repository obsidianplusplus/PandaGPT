package cn.panda.pandagpt.performance;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TickBudgetTest {
    @Test
    void sharesQuotaAndChecksDeadlineBeforeAWorkUnit() {
        long[] time = {100};
        var budget = new TickBudget(20, 2, true, () -> time[0]);
        assertTrue(budget.cell());
        assertTrue(budget.cell());
        assertFalse(budget.cell());
        assertEquals(2, budget.cells());
        var deadline = new TickBudget(20, 100, true, () -> time[0]);
        time[0] = 120;
        assertFalse(deadline.cell());
        assertEquals(0, deadline.cells());
    }

    @Test
    void clockIsSampledPeriodicallyAndExpiryIsSticky() {
        long[] time = {0};
        int[] reads = {0};
        var budget = new TickBudget(100, 10000, true, () -> {
            reads[0]++;
            return time[0];
        });
        int before = reads[0];
        for (int i = 0; i < TickBudget.CLOCK_STRIDE * 4; i++) assertTrue(budget.cell());
        assertTrue(reads[0] - before <= 4, "不应每个格子都读取一次时钟");
        time[0] = 100;
        int extra = 0;
        while (budget.cell()) extra++;
        assertTrue(extra < TickBudget.CLOCK_STRIDE, "超出截止时间的格子数受采样间隔限制");
        time[0] = 0;
        assertFalse(budget.available(), "超时状态一旦出现便保持");
    }

    @Test
    void pressureHasHysteresisAndReducesForegroundBudget() {
        var governor = new LoadGovernor();
        governor.observe(45);
        var budget = governor.budget(2000000, 8192);
        assertFalse(budget.background());
        assertTrue(budget.available());
        for (int i = 0; i < 4; i++) governor.observe(34);
        assertFalse(governor.budget(2000000, 8192).background());
        // 恢复必须连续满足条件。
        governor.observe(40);
        for (int i = 0; i < 4; i++) governor.observe(34);
        assertFalse(governor.budget(2000000, 8192).background());
        governor.observe(34);
        assertTrue(governor.budget(2000000, 8192).background());
        governor.observe(Double.NaN);
        assertTrue(governor.budget(2000000, 8192).background());
    }
}
