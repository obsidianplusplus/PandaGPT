package cn.panda.pandagpt.vision;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneRendererTest {
    @Test void imagesDepthAndSemanticMaskRemainAligned() throws Exception {
        Camera camera = new Camera(new Camera.Vec3(0, 64, 0), 0, 0, 3, 1, 70);
        RaySample stone = new RaySample(RaySample.Kind.HIT, 4, "STONE", "minecraft:stone", "UP",
                0x808080, 15, new Camera.Vec3(0, 64, 4), 0, 64, 4);
        var frame = new SceneRenderer.Frame(UUID.randomUUID(), UUID.randomUUID(), "test", camera, 8, 10, 20,
                List.of(stone, RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE), RaySample.empty(RaySample.Kind.UNSENT_OR_UNLOADED)));
        var result = SceneRenderer.render(frame);
        var image = ImageIO.read(new ByteArrayInputStream(result.colorPng()));
        var depth = ImageIO.read(new ByteArrayInputStream(result.depthPng()));
        assertEquals(3, image.getWidth()); assertEquals(1, image.getHeight());
        assertEquals(0x808080, image.getRGB(0, 0) & 0xFFFFFF);
        assertEquals(128, depth.getRGB(0, 0) & 255);
        var metadata = JsonParser.parseString(result.metadata()).getAsJsonObject();
        assertEquals("[0,1,2]", metadata.getAsJsonArray("mask").toString());
        assertTrue(metadata.getAsJsonArray("distance_blocks").get(1).isJsonNull());
        assertEquals("image/png", result.image().mediaType());
        assertArrayEquals(result.colorPng(), Base64.getDecoder().decode(result.image().base64()));
        assertTrue(result.summary().contains("NOT client screenshot"));
    }
    @Test void rejectsIncompleteFrames() {
        Camera camera = new Camera(new Camera.Vec3(0, 0, 0), 0, 0, 3, 3, 70);
        assertThrows(IllegalArgumentException.class, () -> new SceneRenderer.Frame(UUID.randomUUID(), UUID.randomUUID(), "test", camera, 8, 0, 0, List.of()));
    }
}
