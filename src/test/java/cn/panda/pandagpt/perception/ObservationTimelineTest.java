package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ObservationTimelineTest {
    @Test void chunkIndexIsUpdatedOnReplacementEvictionRemovalAndWorldChanges() {
        var timeline = new ObservationTimeline(1); UUID player = UUID.randomUUID(), next = UUID.randomUUID(), world = UUID.randomUUID();
        var camera = PerceptionFixtures.camera(1, 1);
        var old = PerceptionFixtures.scene(world, 1000, camera, List.of(PerceptionFixtures.hit(-17, 0, 4, "STONE")), List.of());
        var current = PerceptionFixtures.scene(world, 2000, camera, List.of(PerceptionFixtures.hit(32, 0, 4, "STONE")), List.of());
        timeline.accept(player, old); timeline.accept(player, current);
        timeline.invalidate(world, new SceneObjects.Pos(-17, 0, 4)); assertSame(current, timeline.get(player, world, 2000, 1000));
        timeline.accept(next, current); assertNull(timeline.get(player, world, 2000, 1000));
        timeline.remove(next); timeline.invalidate(world, new SceneObjects.Pos(32, 0, 4));
        timeline.accept(next, current); assertSame(current, timeline.get(next, world, 2000, 1000));
        timeline.invalidate(UUID.randomUUID(), new SceneObjects.Pos(32, 0, 4)); assertSame(current, timeline.get(next, world, 2000, 1000));
        timeline.invalidate(world, new SceneObjects.Pos(32, 0, 4)); assertNull(timeline.get(next, world, 2000, 1000));
        timeline.clear(); timeline.accept(next, current); assertSame(current, timeline.get(next, world, 2000, 1000));
    }
    @Test void gapShrinkIsComparedOnlyForTheSameKnownCameraProjection() {
        var timeline = new ObservationTimeline(2); UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        var camera = PerceptionFixtures.camera(5, 5);
        List<RaySample> pixels = new ArrayList<>(Collections.nCopies(25, PerceptionFixtures.hit(0, 0, 4, "STONE")));
        for (int i : new int[]{6, 7, 11, 12}) pixels.set(i, RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE));
        var first = PerceptionFixtures.scene(world, 1000, camera, pixels, List.of()); timeline.accept(player, first);
        pixels.set(6, PerceptionFixtures.hit(1, 0, 4, "OAK_PLANKS"));
        assertTrue(timeline.accept(player, PerceptionFixtures.scene(world, 2000, camera, pixels, List.of())).events().stream().anyMatch(s -> s.contains("decreased 4 -> 3")));
        timeline.accept(player, first); pixels.set(0, RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED));
        assertFalse(timeline.accept(player, PerceptionFixtures.scene(world, 3000, camera, pixels, List.of())).events().stream().anyMatch(s -> s.contains("opening")));
    }
    @Test void comparesWorldCoordinatesRatherThanPixelIndicesAndNeverInfersMissingBlocks() {
        var timeline = new ObservationTimeline(2); UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        var camera = PerceptionFixtures.camera(2, 1);
        var before = PerceptionFixtures.scene(world, 1000, camera, List.of(PerceptionFixtures.hit(0, 0, 4, "STONE"), PerceptionFixtures.hit(1, 0, 4, "FURNACE")), List.of());
        timeline.accept(player, before);
        var moved = PerceptionFixtures.scene(world, 2000, new Camera(camera.origin(), 30, 0, 2, 1, 70),
                List.of(PerceptionFixtures.hit(1, 0, 4, "FURNACE"), RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED)), List.of());
        assertEquals(0, timeline.accept(player, moved).changedBlocks());
        var changed = PerceptionFixtures.scene(world, 3000, camera, List.of(PerceptionFixtures.hit(1, 0, 4, "STONE"), RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE)), List.of());
        assertEquals(1, timeline.accept(player, changed).changedBlocks());
        assertSame(changed, timeline.get(player, world, 3000, 1000));
        timeline.invalidate(world, new SceneObjects.Pos(1, 0, 4)); assertNull(timeline.get(player, world, 3000, 1000));
    }
    @Test void worldChangesExpiryAndPlayerMotionCannotCreateFalseApproach() {
        var timeline = new ObservationTimeline(2); UUID player = UUID.randomUUID(), world = UUID.randomUUID(), entity = UUID.randomUUID();
        Camera a = new Camera(new Camera.Vec3(.5, 1.6, 0), 0, 0, 32, 18, 70), b = new Camera(new Camera.Vec3(.5, 1.6, 2), 0, 0, 32, 18, 70);
        var e = new EntityObservation(entity, "ZOMBIE", "", new Camera.Vec3(.5, 1.6, 5), new SpatialSurvey.Box(.2, .6, 4.7, .8, 2.6, 5.3), false, "", 0, 0, 0, 0, 0, 1000);
        var pixels = Collections.nCopies(32 * 18, RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE));
        timeline.accept(player, PerceptionFixtures.scene(world, 1000, a, pixels, List.of(e)));
        assertTrue(timeline.accept(player, PerceptionFixtures.scene(world, 2000, b, pixels, List.of(e))).events().isEmpty());
        var moving = new EntityObservation(entity, "ZOMBIE", "", new Camera.Vec3(.5, 1.6, 4), new SpatialSurvey.Box(.2, .6, 3.7, .8, 2.6, 4.3),
                true, "", 0, 0, 0, 0, 0, 3000);
        assertTrue(timeline.accept(player, PerceptionFixtures.scene(world, 3000, b, pixels, List.of(moving))).events().stream().anyMatch(s -> s.contains("moved toward")));
        assertNull(timeline.get(player, UUID.randomUUID(), 3000, 1000)); assertNull(timeline.get(player, world, 5000, 1000));
    }
}
