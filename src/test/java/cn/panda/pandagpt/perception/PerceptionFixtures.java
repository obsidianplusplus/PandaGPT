package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.*;
import java.util.*;

final class PerceptionFixtures {
    static RaySample hit(int x, int y, int z, String material) {
        return new RaySample(RaySample.Kind.HIT, 4, material, "minecraft:" + material.toLowerCase(Locale.ROOT), "NORTH", 0xffffff, 15,
                new Camera.Vec3(x + .5, y + .5, z), x, y, z);
    }
    static SpatialSurvey.Report emptyReport() { return new SpatialSurvey.Report(List.of(), "unknown test survey"); }
    static Observation scene(UUID world, long time, Camera camera, List<RaySample> pixels, List<EntityObservation> entities) {
        return Observation.create(UUID.randomUUID(), world, time, camera, 24, pixels, emptyReport(), entities);
    }
    static Camera camera(int width, int height) { return new Camera(new Camera.Vec3(.5, 1.6, 0), 0, 0, width, height, 70); }
}
