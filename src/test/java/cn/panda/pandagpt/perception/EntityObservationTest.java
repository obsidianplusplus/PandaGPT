package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class EntityObservationTest {
    private EntityObservation entity(double x, double z) {
        return new EntityObservation(UUID.randomUUID(), "ZOMBIE", "", new Camera.Vec3(x, 1.6, z),
                new SpatialSurvey.Box(x - .3, .6, z - .3, x + .3, 2.6, z + .3), true, "NEARBY_NOT_VISIBILITY_CHECKED", 0, 0, 0, 0, 0, 1000);
    }
    private String visibility(EntityObservation e, RaySample sample, int range) {
        return EntityObservation.project(List.of(e), PerceptionFixtures.camera(64, 36), range, Collections.nCopies(64 * 36, sample)).getFirst().visibility();
    }
    @Test void entityLabelsRequireSampledVisibilityAndRange() {
        EntityObservation entity = entity(.5, 5);
        assertEquals("VISIBLE_SAMPLES", visibility(entity, RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE), 24));
        assertEquals("UNKNOWN_SAMPLES", visibility(entity, RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED), 24));
        assertEquals("UNKNOWN_SAMPLES", visibility(entity, RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE), 3));
        assertEquals("OCCLUDED_SAMPLES", visibility(entity, PerceptionFixtures.hit(0, 0, 1, "STONE"), 24));
        assertEquals("OUTSIDE_FOV", visibility(entity(.5, -5), RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE), 24));
    }
    @Test void projectionUsesTheSameCameraAndRayIntersectionHandlesParallelAxes() {
        var camera = PerceptionFixtures.camera(64, 36); var pixel = camera.project(camera.origin().add(camera.ray(12, 20).scale(10)));
        assertEquals(12.5, pixel[0], 1e-6); assertEquals(20.5, pixel[1], 1e-6);
        assertEquals(4, EntityObservation.intersect(new SpatialSurvey.Box(0, 0, 4, 1, 2, 5), new Camera.Vec3(.5, 1, 0), new Camera.Vec3(0, 0, 1)));
        assertEquals(Double.POSITIVE_INFINITY, EntityObservation.intersect(new SpatialSurvey.Box(0, 0, 4, 1, 2, 5), new Camera.Vec3(2, 1, 0), new Camera.Vec3(0, 0, 1)));
    }
}
