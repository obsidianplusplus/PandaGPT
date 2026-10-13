package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.vision.Camera.Vec3;

/** Grid DDA. Visitor may stop before any inaccessible cell is read; ties advance all axes. */
public final class VoxelMarcher {
    @FunctionalInterface public interface Visitor {
        boolean visit(int x, int y, int z, double entryDistance, double exitDistance);
    }
    public static void walk(Vec3 origin, Vec3 direction, double maxDistance, Visitor visitor) {
        Vec3 d = direction.normalize();
        int x = (int) Math.floor(origin.x()), y = (int) Math.floor(origin.y()), z = (int) Math.floor(origin.z());
        int sx = sign(d.x()), sy = sign(d.y()), sz = sign(d.z());
        double dx = delta(d.x()), dy = delta(d.y()), dz = delta(d.z());
        double tx = boundary(origin.x(), x, d.x()), ty = boundary(origin.y(), y, d.y()), tz = boundary(origin.z(), z, d.z());
        double entry = 0;
        // L1 traversal distance is at most sqrt(3) times ray length.
        for (int steps = 0; steps <= Math.ceil(maxDistance * 3) + 6; steps++) {
            double next = Math.min(tx, Math.min(ty, tz));
            if (!visitor.visit(x, y, z, entry, Math.min(next, maxDistance))) return;
            if (next > maxDistance || !Double.isFinite(next)) return;
            if (Math.abs(tx - next) < 1e-9) { x += sx; tx += dx; }
            if (Math.abs(ty - next) < 1e-9) { y += sy; ty += dy; }
            if (Math.abs(tz - next) < 1e-9) { z += sz; tz += dz; }
            entry = next;
        }
    }
    private static int sign(double d) { return d > 0 ? 1 : d < 0 ? -1 : 0; }
    private static double delta(double d) { return d == 0 ? Double.POSITIVE_INFINITY : Math.abs(1 / d); }
    private static double boundary(double o, int cell, double d) {
        return d == 0 ? Double.POSITIVE_INFINITY : ((d > 0 ? cell + 1 : cell) - o) / d;
    }
    private VoxelMarcher() {}
}
