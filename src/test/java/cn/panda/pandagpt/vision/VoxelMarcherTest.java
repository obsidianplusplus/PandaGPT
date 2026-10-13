package cn.panda.pandagpt.vision;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoxelMarcherTest {
    @Test void traversesInDistanceOrderAndStopsAtTheFirstHit() {
        List<Integer> cells = new ArrayList<>();
        VoxelMarcher.walk(new Camera.Vec3(.5, .5, .5), new Camera.Vec3(0, 0, 1), 10,
                (x, y, z, entry, exit) -> { cells.add(z); assertTrue(exit >= entry); return z < 2; });
        assertEquals(List.of(0, 1, 2), cells);
    }
    @Test void handlesNegativeCoordinatesAndCornerTies() {
        List<String> cells = new ArrayList<>();
        VoxelMarcher.walk(new Camera.Vec3(-.5, -.5, -.5), new Camera.Vec3(-1, -1, -1), 2,
                (x, y, z, entry, exit) -> { cells.add(x + "," + y + "," + z); return true; });
        assertEquals(List.of("-1,-1,-1", "-2,-2,-2"), cells);
    }
    @Test void inaccessibleCellStopsTraversalInsteadOfInventingDataBehindIt() {
        int[] reads = {0};
        VoxelMarcher.walk(new Camera.Vec3(15.5, 64.5, .5), new Camera.Vec3(1, 0, 0), 96,
                (x, y, z, entry, exit) -> { reads[0]++; return x < 16; });
        assertEquals(2, reads[0]);
    }
}
