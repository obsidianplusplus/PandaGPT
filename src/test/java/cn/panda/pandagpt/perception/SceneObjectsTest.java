package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneObjectsTest {
    @Test void adjacentFurnacesStaySeparateAndScreenSidesRemainGrounded() {
        var pixels = List.of(PerceptionFixtures.hit(0, 0, 4, "FURNACE"), PerceptionFixtures.hit(0, 0, 4, "FURNACE"),
                PerceptionFixtures.hit(1, 0, 4, "FURNACE"), PerceptionFixtures.hit(1, 0, 4, "FURNACE"));
        var result = SceneObjects.analyze(PerceptionFixtures.camera(4, 1), pixels);
        assertEquals(2, result.objects().size()); assertEquals(.25, result.get(1).screenX()); assertEquals(.75, result.get(2).screenX());
        assertEquals(new SceneObjects.Pos(0, 0, 4), result.get(1).blocks().getFirst());
        assertArrayEquals(new int[]{1, 1, 2, 2}, result.objectPixels());
        int[] leaked = result.objectPixels(); leaked[0] = 99; assertEquals(1, result.objectPixels()[0]);
        assertNull(result.get(99));
    }
    @Test void connectedSurfacesMergeButUnknownPixelsDoNotBecomeObjects() {
        var pixels = List.of(PerceptionFixtures.hit(0, 0, 4, "STONE"), PerceptionFixtures.hit(1, 0, 4, "STONE"),
                RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED), PerceptionFixtures.hit(3, 0, 4, "STONE"));
        var result = SceneObjects.analyze(PerceptionFixtures.camera(4, 1), pixels);
        assertEquals(2, result.objects().size()); assertEquals(2, result.get(1).blocks().size()); assertEquals(0, result.objectPixels()[2]);
    }
    @Test void onlyEnclosedKnownNoHitRegionsAreOpeningCandidates() {
        List<RaySample> pixels = new ArrayList<>(Collections.nCopies(25, PerceptionFixtures.hit(0, 0, 4, "STONE")));
        for (int i : new int[]{6, 7, 11, 12}) pixels.set(i, RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE));
        assertEquals(1, SceneObjects.analyze(PerceptionFixtures.camera(5, 5), pixels).openingCandidates().size());
        pixels.set(8, RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED));
        assertTrue(SceneObjects.analyze(PerceptionFixtures.camera(5, 5), pixels).openingCandidates().isEmpty());
        pixels.set(8, PerceptionFixtures.hit(0, 0, 4, "STONE")); pixels.set(1, RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE));
        assertTrue(SceneObjects.analyze(PerceptionFixtures.camera(5, 5), pixels).openingCandidates().isEmpty());
    }
}
