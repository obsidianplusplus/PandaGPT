package cn.panda.pandagpt.vision;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class VoxelMarcherTest {
    @Test
    void suspendingAtEveryCellPreservesExactTraversalIncludingNegativeTies() {
        for (var direction :
                List.of(new Camera.Vec3(-1, -1, -1), new Camera.Vec3(.3, -.7, 1), new Camera.Vec3(0, 0, 1))) {
            var origin = new Camera.Vec3(-.5, 64.5, -.5);
            List<String> full = new ArrayList<>(), sliced = new ArrayList<>();
            VoxelMarcher.walk(origin, direction, 96, (x, y, z, a, b) -> {
                full.add(x + "," + y + "," + z + ":" + a + ":" + b);
                return true;
            });
            var cursor = new VoxelMarcher.Cursor(origin, direction, 96);
            boolean done = false;
            while (!done) {
                int[] available = {1};
                done = cursor.advance(() -> available[0]-- > 0, (x, y, z, a, b) -> {
                    sliced.add(x + "," + y + "," + z + ":" + a + ":" + b);
                    return true;
                });
            }
            assertEquals(full, sliced);
            assertTrue(cursor.advance(() -> false, (x, y, z, a, b) -> fail("Completed cursor reread a cell")));
        }
    }

    @Test
    void traversesInDistanceOrderAndStopsAtTheFirstHit() {
        List<Integer> cells = new ArrayList<>();
        VoxelMarcher.walk(new Camera.Vec3(.5, .5, .5), new Camera.Vec3(0, 0, 1), 10, (x, y, z, entry, exit) -> {
            cells.add(z);
            assertTrue(exit >= entry);
            return z < 2;
        });
        assertEquals(List.of(0, 1, 2), cells);
    }

    @Test
    void handlesNegativeCoordinatesAndCornerTies() {
        List<String> cells = new ArrayList<>();
        VoxelMarcher.walk(new Camera.Vec3(-.5, -.5, -.5), new Camera.Vec3(-1, -1, -1), 2, (x, y, z, entry, exit) -> {
            cells.add(x + "," + y + "," + z);
            return true;
        });
        assertEquals(List.of("-1,-1,-1", "-2,-2,-2"), cells);
    }

    @Test
    void inaccessibleCellStopsTraversalInsteadOfInventingDataBehindIt() {
        int[] reads = {0};
        VoxelMarcher.walk(new Camera.Vec3(15.5, 64.5, .5), new Camera.Vec3(1, 0, 0), 96, (x, y, z, entry, exit) -> {
            reads[0]++;
            return x < 16;
        });
        assertEquals(2, reads[0]);
    }
}
