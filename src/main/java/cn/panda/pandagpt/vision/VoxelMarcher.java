package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.vision.Camera.Vec3;
import java.util.function.BooleanSupplier;

/** Resumable grid DDA. A suspended cell is never reported as a miss. Ties advance all axes. */
public final class VoxelMarcher {
    @FunctionalInterface public interface Visitor {
        boolean visit(int x, int y, int z, double entryDistance, double exitDistance);
    }
    public static final class Cursor {
        private int x, y, z, steps;
        private final int sx, sy, sz, maxSteps;
        private final double dx, dy, dz, range;
        private double tx, ty, tz, entry;
        private boolean done;
        public Cursor(Vec3 origin, Vec3 direction, double range) {
            Vec3 d = direction.normalize(); this.range = range;
            x = (int) Math.floor(origin.x()); y = (int) Math.floor(origin.y()); z = (int) Math.floor(origin.z());
            sx = sign(d.x()); sy = sign(d.y()); sz = sign(d.z());
            dx = delta(d.x()); dy = delta(d.y()); dz = delta(d.z());
            tx = boundary(origin.x(), x, d.x()); ty = boundary(origin.y(), y, d.y()); tz = boundary(origin.z(), z, d.z());
            maxSteps = (int) Math.ceil(range * 3) + 6;
        }
        /** Returns false only when the budget suspends traversal, before accessing the next cell. */
        public boolean advance(BooleanSupplier permitCell, Visitor visitor) {
            while (!done) {
                if (!permitCell.getAsBoolean()) return false;
                double next = Math.min(tx, Math.min(ty, tz));
                if (!visitor.visit(x, y, z, entry, Math.min(next, range)) || next > range
                        || !Double.isFinite(next) || ++steps > maxSteps) { done = true; break; }
                if (Math.abs(tx - next) < 1e-9) { x += sx; tx += dx; }
                if (Math.abs(ty - next) < 1e-9) { y += sy; ty += dy; }
                if (Math.abs(tz - next) < 1e-9) { z += sz; tz += dz; }
                entry = next;
            }
            return true;
        }
    }
    public static void walk(Vec3 origin, Vec3 direction, double maxDistance, Visitor visitor) {
        new Cursor(origin, direction, maxDistance).advance(() -> true, visitor);
    }
    private static int sign(double d) { return d > 0 ? 1 : d < 0 ? -1 : 0; }
    private static double delta(double d) { return d == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d); }
    private static double boundary(double o, int cell, double d) {
        return d == 0 ? Double.POSITIVE_INFINITY : ((d > 0 ? cell + 1 : cell) - o) / d;
    }
    private VoxelMarcher() {}
}
