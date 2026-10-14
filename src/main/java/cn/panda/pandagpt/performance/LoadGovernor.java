package cn.panda.pandagpt.performance;

import java.util.Arrays;

/**
 * 基于 MSPT 的负载调节器，带滞回：达到 45 MSPT 时暂停后台任务，连续 5 秒低于 35 MSPT 才恢复。
 *
 * <p>线程：仅限服务器主线程。
 */
public final class LoadGovernor {
    private static final double PRESSURE_MSPT = 45;
    private static final double RECOVERY_MSPT = 35;
    private static final int RECOVERY_SAMPLES = 5;

    private boolean pressured;
    private int healthy;
    private final long[] times = new long[512];
    private int cursor;
    private int size;
    private long max;
    private long cells;
    private long deferred;

    /** 每秒调用一次，传入服务器平均 tick 耗时。 */
    public void observe(double mspt) {
        if (!Double.isFinite(mspt) || mspt < 0) {
            return;
        }
        if (mspt >= PRESSURE_MSPT) {
            pressured = true;
            healthy = 0;
        } else if (pressured) {
            // 恢复必须连续满足条件，中途任何一次偏高都会重新计数。
            healthy = mspt < RECOVERY_MSPT ? healthy + 1 : 0;
            if (healthy >= RECOVERY_SAMPLES) {
                pressured = false;
            }
        }
    }

    /** 高负载时把时间与格子预算缩减为四分之一，并禁止后台任务。 */
    public TickBudget budget(long nanos, int cells) {
        return new TickBudget(
                pressured ? Math.max(100_000, nanos / 4) : nanos,
                pressured ? Math.max(16, cells / 4) : cells,
                !pressured);
    }

    public void record(TickBudget budget) {
        long elapsed = budget.elapsed();
        times[cursor++ % times.length] = elapsed;
        size = Math.min(times.length, size + 1);
        max = Math.max(max, elapsed);
        cells += budget.cells();
        if (!budget.background()) {
            deferred++;
        }
    }

    public String status() {
        long[] sorted = Arrays.copyOf(times, size);
        Arrays.sort(sorted);
        long p95 = size == 0 ? 0 : sorted[(int) Math.ceil(size * .95) - 1];
        return "pressure=" + pressured + ", loop_p95_ms=" + millis(p95) + ", loop_max_ms=" + millis(max)
                + ", visited_cells=" + cells + ", shed_ticks=" + deferred;
    }

    private static double millis(long nanos) {
        return Math.round(nanos / 10000.0) / 100.0;
    }
}
