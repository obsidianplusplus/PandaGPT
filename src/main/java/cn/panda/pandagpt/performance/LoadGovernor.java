package cn.panda.pandagpt.performance;

import java.util.Arrays;

/** Hysteresis: shed ambient work at 45 MSPT; recover only after five healthy seconds below 35 MSPT. */
public final class LoadGovernor {
    private boolean pressured;
    private int healthy;
    private final long[] times = new long[512];
    private int cursor, size;
    private long max, cells, deferred;
    public void observe(double mspt) {
        if (!Double.isFinite(mspt) || mspt < 0) return;
        if (mspt >= 45) { pressured = true; healthy = 0; }
        else if (pressured) { healthy = mspt < 35 ? healthy + 1 : 0; if (healthy >= 5) pressured = false; }
    }
    public TickBudget budget(long nanos, int cells) {
        return new TickBudget(pressured ? Math.max(100000, nanos / 4) : nanos,
                pressured ? Math.max(16, cells / 4) : cells, !pressured);
    }
    public void record(TickBudget budget) {
        long elapsed = budget.elapsed(); times[cursor++ % times.length] = elapsed; size = Math.min(times.length, size + 1);
        max = Math.max(max, elapsed); cells += budget.cells(); if (!budget.background()) deferred++;
    }
    public String status() {
        long[] sorted = Arrays.copyOf(times, size); Arrays.sort(sorted);
        return "pressure=" + pressured + ", loop_p95_ms=" + millis(size == 0 ? 0 : sorted[(int) Math.ceil(size * .95) - 1])
                + ", loop_max_ms=" + millis(max) + ", visited_cells=" + cells + ", shed_ticks=" + deferred;
    }
    private static double millis(long nanos) { return Math.round(nanos / 10000.0) / 100.0; }
}
