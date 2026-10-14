package cn.panda.pandagpt.performance;

import java.util.function.LongSupplier;

/** One deadline and one cell quota shared by all captures, surveys, marks and ambient work. */
public final class TickBudget {
    private final LongSupplier clock;
    private final long started, deadline;
    private final int maxCells;
    private final boolean background;
    private int cells;
    public TickBudget(long nanos, int maxCells, boolean background) { this(nanos, maxCells, background, System::nanoTime); }
    public TickBudget(long nanos, int maxCells, boolean background, LongSupplier clock) {
        this.clock = clock; started = clock.getAsLong(); deadline = started + nanos;
        this.maxCells = maxCells; this.background = background;
    }
    public boolean available() { return cells < maxCells && clock.getAsLong() < deadline; }
    public boolean cell() { if (!available()) return false; cells++; return true; }
    public boolean background() { return background; }
    public int cells() { return cells; }
    public long elapsed() { return Math.max(0, clock.getAsLong() - started); }
}
