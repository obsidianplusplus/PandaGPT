package cn.panda.pandagpt.vision.texture;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.*;

import cn.panda.pandagpt.perception.*;
import cn.panda.pandagpt.vision.*;
import cn.panda.pandagpt.vision.Camera.Vec3;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/** 本地可选；CI 会下载经 SHA-1 校验的 26.3 官方资源并运行这些检查。 */
class OfficialAssetsTest {
    private Path path() {
        String value = System.getProperty("pandagpt.test.assets");
        assumeTrue(value != null, "Supply -Dpandagpt.test.assets=<official pack>");
        return Path.of(value);
    }

    @Test
    void stoneAndLogEndsUseTheActualOfficialPngTexels() throws Exception {
        try (var textures = new BlockTextures(new ResourcePack(List.of(path()), 64));
                var reference = new ResourcePack(List.of(path()), 64)) {
            assertEquals(
                    reference.sprite("block/stone").sample(8, 8),
                    textures.sample(TextureFixtures.hit("minecraft:stone", "NORTH", .5, .5, 0))
                            .argb());
            assertEquals(
                    reference.sprite("block/oak_log_top").sample(8, 8),
                    textures.sample(TextureFixtures.hit("minecraft:oak_log[axis=y]", "UP", .5, 1, .5))
                            .argb());
            assertEquals(
                    reference.sprite("block/oak_log_top").sample(8, 8),
                    textures.sample(TextureFixtures.hit("minecraft:oak_log[axis=x]", "EAST", 1, .5, .5))
                            .argb());
            assertEquals(
                    reference.sprite("block/oak_log").sample(8, 8),
                    textures.sample(TextureFixtures.hit("minecraft:oak_log[axis=y]", "NORTH", .5, .5, 0))
                            .argb());
        }
    }

    @Test
    void slabsStairsAndDirectionalFurnacesResolveTheirRealModels() throws Exception {
        try (var textures = new BlockTextures(new ResourcePack(List.of(path()), 64));
                var reference = new ResourcePack(List.of(path()), 64)) {
            assertEquals(
                    reference.sprite("block/oak_planks").sample(8, 8),
                    textures.sample(TextureFixtures.hit(
                                    "minecraft:oak_slab[type=bottom,waterlogged=false]", "UP", .5, .5, .5))
                            .argb());
            assertEquals(
                    reference.sprite("block/oak_planks").sample(4, 12),
                    textures.sample(TextureFixtures.hit(
                                    "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
                                    "UP",
                                    .25,
                                    .5,
                                    .75))
                            .argb());
            assertEquals(
                    reference.sprite("block/furnace_front").sample(8, 8),
                    textures.sample(TextureFixtures.hit(
                                    "minecraft:furnace[facing=north,lit=false]", "NORTH", .5, .5, 0))
                            .argb());
            assertEquals(
                    reference.sprite("block/furnace_front").sample(8, 8),
                    textures.sample(TextureFixtures.hit("minecraft:furnace[facing=east,lit=false]", "EAST", 1, .5, .5))
                            .argb());
        }
    }

    @Test
    void syntheticRaycastSceneRendersRealTexturesAndAlignedMetadata() throws Exception {
        Camera camera = new Camera(new Vec3(.5, 2.6, -5), 0, 8, 320, 180, 60);
        List<RaySample> pixels = new ArrayList<>();
        for (int y = 0; y < camera.height(); y++)
            for (int x = 0; x < camera.width(); x++) {
                Vec3 ray = camera.ray(x, y);
                RaySample[] sample = {RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE)};
                VoxelMarcher.walk(camera.origin(), ray, 24, (bx, by, bz, entry, exit) -> {
                    String state = sceneBlock(bx, by, bz);
                    if (state == null) return true;
                    Vec3 hit = camera.origin().add(ray.scale(entry));
                    double hx = hit.x() - bx, hy = hit.y() - by, hz = hit.z() - bz;
                    String face = Math.abs(hy - 1) < 1e-7
                            ? "UP"
                            : Math.abs(hy) < 1e-7
                                    ? "DOWN"
                                    : Math.abs(hz) < 1e-7
                                            ? "NORTH"
                                            : Math.abs(hz - 1) < 1e-7 ? "SOUTH" : Math.abs(hx) < 1e-7 ? "WEST" : "EAST";
                    sample[0] = new RaySample(
                            RaySample.Kind.HIT,
                            entry,
                            state.split("[\\[]")[0],
                            state,
                            face,
                            0xffffff,
                            15,
                            hit,
                            bx,
                            by,
                            bz);
                    return false;
                });
                pixels.add(sample[0]);
            }
        try (var textures = new BlockTextures(new ResourcePack(List.of(path()), 64))) {
            var frame = new SceneRenderer.Frame(
                    UUID.randomUUID(), UUID.randomUUID(), "synthetic-validation-scene", camera, 24, 0, 0, pixels);
            var rendered = SceneRenderer.render(frame, textures);
            var metadata = JsonParser.parseString(rendered.metadata()).getAsJsonObject();
            assertEquals(0, metadata.get("missing_texture_pixels").getAsInt());
            assertEquals("resource_pack_png", metadata.get("texture_mode").getAsString());
            assertEquals(320 * 180, metadata.getAsJsonArray("texture_mask").size());
            assertTrue(
                    pixels.stream().filter(p -> p.kind() == RaySample.Kind.HIT).count() > 20000);
            var image = ImageIO.read(new ByteArrayInputStream(rendered.colorPng()));
            Set<Integer> stoneColors = new HashSet<>();
            for (int yy = 0; yy < image.getHeight(); yy++)
                for (int xx = 0; xx < image.getWidth(); xx++)
                    if (pixels.get(yy * camera.width() + xx).blockData().equals("minecraft:stone"))
                        stoneColors.add(image.getRGB(xx, yy));
            Files.createDirectories(Path.of("target"));
            Files.write(Path.of("target/texture-preview.png"), rendered.colorPng());
            assertTrue(
                    stoneColors.size() > 1,
                    "Stone PNG variation: colors=" + stoneColors.size() + ", hits="
                            + pixels.stream()
                                    .filter(p -> p.blockData().equals("minecraft:stone"))
                                    .count());
            var entity = new EntityObservation(
                    UUID.randomUUID(),
                    "VILLAGER",
                    "",
                    new Vec3(.5, .9, 1.5),
                    new SpatialSurvey.Box(.2, 0, 1.2, .8, 1.8, 1.8),
                    false,
                    "",
                    0,
                    0,
                    0,
                    0,
                    0,
                    0);
            var observation = Observation.create(
                    frame.captureId(),
                    UUID.randomUUID(),
                    0,
                    camera,
                    24,
                    pixels,
                    new SpatialSurvey.Report(List.of(), "Synthetic scene; no local world survey"),
                    List.of(entity));
            var annotated = SceneRenderer.render(frame, textures, observation);
            assertEquals("VISIBLE_SAMPLES", observation.entities().getFirst().visibility());
            var annotatedMetadata = JsonParser.parseString(annotated.metadata()).getAsJsonObject();
            assertEquals(
                    320 * 180,
                    annotatedMetadata.getAsJsonArray("scene_object_id_pixels").size());
            assertTrue(annotatedMetadata.getAsJsonArray("scene_objects").size() > 4);
            Files.write(Path.of("target/perception-preview.png"), annotated.colorPng());
        }
    }

    private static String sceneBlock(int x, int y, int z) {
        if (y == -1 && Math.abs(x) < 14 && z >= -5 && z <= 12) return "minecraft:grass_block[snowy=false]";
        if (z == 6 && Math.abs(x) < 7 && y >= 0 && y < 4) {
            if (Math.abs(x) == 6) return "minecraft:oak_log[axis=y]";
            if (y == 0) return "minecraft:stone_bricks";
            return x < -2 ? "minecraft:bricks" : x > 2 ? "minecraft:oak_planks" : "minecraft:stone";
        }
        if (x >= 1 && x <= 2 && z >= 2 && z <= 3 && y == 0) return "minecraft:oak_planks";
        if (x == -2 && z == 3 && y == 0) return "minecraft:furnace[facing=north,lit=false]";
        if (x == 4 && z == 3 && y >= 0 && y < 3) return "minecraft:oak_log[axis=y]";
        return null;
    }
}
