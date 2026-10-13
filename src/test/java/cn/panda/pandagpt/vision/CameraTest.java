package cn.panda.pandagpt.vision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CameraTest {
    @Test void centerMatchesMinecraftYawAndPitchWithoutMirroring() {
        for (double yaw : new double[]{0, 90, 180, 270}) {
            Camera camera = new Camera(new Camera.Vec3(0, 0, 0), yaw, 0, 3, 3, 70);
            assertEquals(1, camera.forward().dot(camera.ray(1, 1)), 1e-12);
        }
        Camera south = new Camera(new Camera.Vec3(0, 0, 0), 0, 0, 3, 3, 70);
        assertTrue(south.ray(0, 1).x() > 0); // Looking south, world +X is screen left.
        assertTrue(south.ray(1, 0).y() > 0);
        Camera down = new Camera(new Camera.Vec3(0, 0, 0), 0, 90, 3, 3, 70);
        assertEquals(-1, down.ray(1, 1).y(), 1e-12);
        assertEquals(1, down.ray(0, 0).length(), 1e-12);
    }
    @Test void verticalFovAndAspectProducePerspectiveRays() {
        Camera camera = new Camera(new Camera.Vec3(0, 0, 0), 0, 0, 160, 90, 70);
        var left = camera.ray(0, 44);
        assertEquals((1 - 1.0 / 160) * Math.tan(Math.toRadians(35)) * 160 / 90, left.x() / left.z(), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> camera.ray(160, 0));
    }
}
