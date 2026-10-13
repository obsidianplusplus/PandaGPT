package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.*;
import cn.panda.pandagpt.vision.Camera.Vec3;
import org.bukkit.entity.*;
import java.util.*;

/** Entities are server observations and sample-verified annotations, not rendered client entity models. */
public record EntityObservation(UUID uuid, String type, String item, Vec3 center, SpatialSurvey.Box box,
                                boolean targetingPlayer, String visibility, int left, int top, int right, int bottom,
                                int visibleSamples, long observedAt) {
    public static List<EntityObservation> capture(Player player, PerceptionSettings settings, long time) {
        var origin = player.getEyeLocation(); var world = player.getWorld(); Set<Long> sent = player.getSentChunkKeys();
        List<EntityObservation> list = new ArrayList<>(); int scanned = 0;
        for (Entity e : player.getNearbyEntities(settings.entityRadius(), settings.entityRadius(), settings.entityRadius())) {
            if (++scanned > 256) break;
            if (e == player || !e.isValid() || !player.canSee(e)) continue;
            var location = e.getLocation(); int x = location.getBlockX(), z = location.getBlockZ();
            if (!sent.contains(BlockRaycaster.chunkKey(x >> 4, z >> 4)) || !world.isChunkLoaded(x >> 4, z >> 4)) continue;
            if (location.distanceSquared(origin) > settings.entityRadius() * settings.entityRadius()) continue;
            var b = e.getBoundingBox(); Vec3 center = new Vec3(b.getCenterX(), b.getCenterY(), b.getCenterZ());
            String item = e instanceof Item dropped ? dropped.getItemStack().getType().name() + " x" + dropped.getItemStack().getAmount() : "";
            boolean target = e instanceof Mob mob && mob.getTarget() != null && mob.getTarget().getUniqueId().equals(player.getUniqueId());
            list.add(new EntityObservation(e.getUniqueId(), e.getType().name(), item, center,
                    new SpatialSurvey.Box(b.getMinX(), b.getMinY(), b.getMinZ(), b.getMaxX(), b.getMaxY(), b.getMaxZ()),
                    target, "NEARBY_NOT_VISIBILITY_CHECKED", 0, 0, 0, 0, 0, time));
        }
        Vec3 eye = new Vec3(origin.getX(), origin.getY(), origin.getZ());
        return list.stream().sorted(Comparator.comparingDouble(e -> e.center.distance(eye))).limit(settings.maxEntities()).toList();
    }
    public static List<EntityObservation> project(List<EntityObservation> entities, Camera camera, int range, List<RaySample> pixels) {
        List<EntityObservation> result = new ArrayList<>();
        for (EntityObservation e : entities) {
            double minX = Double.POSITIVE_INFINITY, minY = minX, maxX = Double.NEGATIVE_INFINITY, maxY = maxX;
            for (double x : new double[]{e.box.x0(), e.box.x1()}) for (double y : new double[]{e.box.y0(), e.box.y1()}) for (double z : new double[]{e.box.z0(), e.box.z1()}) {
                double[] p = camera.project(new Vec3(x, y, z)); if (p == null) continue;
                minX = Math.min(minX, p[0]); maxX = Math.max(maxX, p[0]); minY = Math.min(minY, p[1]); maxY = Math.max(maxY, p[1]);
            }
            if (!Double.isFinite(minX) || maxX < 0 || minX >= camera.width() || maxY < 0 || minY >= camera.height()) {
                result.add(e.with("OUTSIDE_FOV", 0, 0, 0, 0, 0)); continue;
            }
            int l = (int) Math.clamp(Math.floor(minX), 0, camera.width() - 1), r = (int) Math.clamp(Math.ceil(maxX), 0, camera.width() - 1);
            int t = (int) Math.clamp(Math.floor(minY), 0, camera.height() - 1), b = (int) Math.clamp(Math.ceil(maxY), 0, camera.height() - 1);
            int visible = 0, known = 0;
            for (int ix = 0; ix < 3; ix++) for (int iy = 0; iy < 3; iy++) {
                int px = l + (r - l) * (ix + 1) / 4, py = t + (b - t) * (iy + 1) / 4;
                double distance = intersect(e.box, camera.origin(), camera.ray(px, py)); if (!Double.isFinite(distance) || distance > range) continue;
                RaySample p = pixels.get(py * camera.width() + px);
                if (p.kind() == RaySample.Kind.UNSENT_OR_UNLOADED) continue;
                known++;
                if (p.kind() == RaySample.Kind.NO_HIT_WITHIN_RANGE || distance + .03 < p.distance()) visible++;
            }
            result.add(e.with(visible > 0 ? "VISIBLE_SAMPLES" : known > 0 ? "OCCLUDED_SAMPLES" : "UNKNOWN_SAMPLES", l, t, r, b, visible));
        }
        return List.copyOf(result);
    }
    private EntityObservation with(String visibility, int l, int t, int r, int b, int n) {
        return new EntityObservation(uuid, type, item, center, box, targetingPlayer, visibility, l, t, r, b, n, observedAt);
    }
    public static double intersect(SpatialSurvey.Box box, Vec3 o, Vec3 d) {
        double near = 0, far = Double.POSITIVE_INFINITY;
        double[] origin = {o.x(), o.y(), o.z()}, direction = {d.x(), d.y(), d.z()};
        double[] min = {box.x0(), box.y0(), box.z0()}, max = {box.x1(), box.y1(), box.z1()};
        for (int a = 0; a < 3; a++) {
            if (Math.abs(direction[a]) < 1e-9) { if (origin[a] < min[a] || origin[a] > max[a]) return Double.POSITIVE_INFINITY; }
            else {
                double p = (min[a] - origin[a]) / direction[a], q = (max[a] - origin[a]) / direction[a];
                near = Math.max(near, Math.min(p, q)); far = Math.min(far, Math.max(p, q));
                if (near > far) return Double.POSITIVE_INFINITY;
            }
        }
        return near;
    }
}
