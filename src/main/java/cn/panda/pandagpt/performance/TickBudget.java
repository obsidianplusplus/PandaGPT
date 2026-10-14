package cn.panda.pandagpt.performance;

import java.util.function.LongSupplier;

/**
 * 单个 tick 内所有采样、调查、标记与后台任务共享的一个截止时间和一份格子配额。
 *
 * <p>为降低开销，每 {@value #CLOCK_STRIDE} 次检查才读取一次时钟；一旦超时便保持超时状态。线程：仅限服务器主线程。
 */
public final class TickBudget {
    /** 两次读取时钟之间允许的检查次数。 */
    static final int CLOCK_STRIDE = 16;

    private final LongSupplier clock;
    private final long started;
    private final long deadline;
    private final int maxCells;
    private final boolean background;
    private int cells;
    private int probes;
    private boolean expired;

    public TickBudget(long nanos, int maxCells, boolean background) {
        this(nanos, maxCells, background, System::nanoTime);
    }

    public TickBudget(long nanos, int maxCells, boolean background, LongSupplier clock) {
        this.clock = clock;
        started = clock.getAsLong();
        deadline = started + nanos;
        this.maxCells = maxCells;
        this.background = background;
        expired = nanos <= 0;
    }

    /** 配额与时间是否仍有剩余；不消耗配额。 */
    public boolean available() {
        if (expired || cells >= maxCells) {
            return false;
        }
        if (probes++ % CLOCK_STRIDE == 0 && clock.getAsLong() >= deadline) {
            expired = true;
        }
        return !expired;
    }

    /** 消耗一个格子配额；预算耗尽时返回 false 且不消耗。 */
    public boolean cell() {
        if (!available()) {
            return false;
        }
        cells++;
        return true;
    }

    /** 服务器未处于高负载时才允许执行后台（环境观察）任务。 */
    public boolean background() {
        return background;
    }

    public int cells() {
        return cells;
    }

    public long elapsed() {
        return Math.max(0, clock.getAsLong() - started);
    }
}
