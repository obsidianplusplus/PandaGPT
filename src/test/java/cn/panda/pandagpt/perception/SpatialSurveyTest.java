package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.Camera.Vec3;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SpatialSurveyTest {
    @Test void roomConnectivityReportsOnlyProvenLocalCollisionEnclosure() {
        var cells = ground(0);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) for (int y = 0; y <= 2; y++)
            if (x == -1 || x == 1 || z == -1 || z == 1 || y == 2)
                cells.put(new SceneObjects.Pos(x, y, z), new SpatialSurvey.Cell(true, "STONE", List.of(new SpatialSurvey.Box(x, y, z, x + 1, y + 1, z + 1))));
        var feet = new Vec3(.5, 0, .5);
        assertEquals("ENCLOSED_BY_FULL_COLLISIONS_LOCALLY", SpatialSurvey.analyze(cells, feet, 0).enclosure().status());
        cells.put(new SceneObjects.Pos(1, 0, 0), new SpatialSurvey.Cell(true, "AIR", List.of()));
        assertEquals("CONNECTED_TO_SURVEY_EDGE", SpatialSurvey.analyze(cells, feet, 0).enclosure().status());
        cells.put(new SceneObjects.Pos(1, 0, 0), new SpatialSurvey.Cell(true, "OAK_FENCE", List.of(new SpatialSurvey.Box(1.4, 0, .4, 1.6, 1, .6))));
        assertEquals("UNKNOWN_BOUNDARY", SpatialSurvey.analyze(cells, feet, 0).enclosure().status());
    }
    private Map<SceneObjects.Pos, SpatialSurvey.Cell> ground(double top) {
        Map<SceneObjects.Pos, SpatialSurvey.Cell> cells = new HashMap<>();
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) for (int y = -4; y <= 2; y++)
            cells.put(new SceneObjects.Pos(x, y, z), new SpatialSurvey.Cell(true, y < top ? "STONE" : "AIR",
                    y < top && y + 1 >= top ? List.of(new SpatialSurvey.Box(x, y, z, x + 1, top, z + 1)) : List.of()));
        return cells;
    }
    private SpatialSurvey.Route center(Map<SceneObjects.Pos, SpatialSurvey.Cell> cells) {
        return SpatialSurvey.analyze(cells, new Vec3(.5, 0, .5), 0).routes().stream().filter(r -> r.ahead() == 1 && r.lateral() == 0).findFirst().orElseThrow();
    }
    @Test void distinguishesStandingClearanceDropAndUnknown() {
        var cells = ground(0); assertEquals("PASSABLE_LOCAL", center(cells).result());
        cells.put(new SceneObjects.Pos(0, 1, 1), new SpatialSurvey.Cell(true, "STONE", List.of(new SpatialSurvey.Box(0, 1, 1, 1, 2, 2))));
        assertEquals("BLOCKED", center(cells).result());
        assertEquals("DROP", center(ground(-2)).result()); assertEquals(-2, center(ground(-2)).floorY());
        cells = ground(0); cells.remove(new SceneObjects.Pos(0, 2, 1)); assertEquals("UNKNOWN", center(cells).result());
    }
    @Test void slabHeightAndLavaAreNotFlattenedIntoOrdinaryAir() {
        assertEquals("PASSABLE_LOCAL", center(ground(.5)).result()); assertEquals(.5, center(ground(.5)).floorY());
        var cells = ground(0); cells.put(new SceneObjects.Pos(0, 0, 1), new SpatialSurvey.Cell(true, "LAVA", List.of()));
        assertEquals("HAZARD", center(cells).result());
    }
    @Test void unsentSurveyNeverReadsWorldBlocksAndRemainsBounded() {
        World world = mock(World.class);
        var survey = new SpatialSurvey(world, Set.of(), new Vec3(-.5, 64, -.5), 0);
        for (int i = 0; i < 343; i++) survey.step(); assertTrue(survey.done()); survey.step();
        assertTrue(survey.report().routes().stream().allMatch(r -> r.result().equals("UNKNOWN")));
        verify(world, never()).getBlockAt(anyInt(), anyInt(), anyInt()); verify(world, never()).isChunkLoaded(anyInt(), anyInt());
    }
}
