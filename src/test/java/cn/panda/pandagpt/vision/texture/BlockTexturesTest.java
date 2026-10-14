package cn.panda.pandagpt.vision.texture;

import static org.junit.jupiter.api.Assertions.*;

import cn.panda.pandagpt.vision.*;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlockTexturesTest {
    @TempDir
    Path folder;

    @Test
    void rendererMarksMissingTexturesAndTransparentTexelsWithoutFlatColorFallback() throws Exception {
        TextureFixtures.cube(folder);
        TextureFixtures.texture(folder, "block/test", 0x80ffffff);
        Camera camera = new Camera(new Camera.Vec3(0, 0, 0), 0, 0, 3, 1, 70);
        var frame = new SceneRenderer.Frame(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "test",
                camera,
                8,
                0,
                0,
                List.of(
                        TextureFixtures.hit("minecraft:test", "UP", .5, 1, .5),
                        TextureFixtures.hit("minecraft:missing", "UP", .5, 1, .5),
                        RaySample.empty(RaySample.Kind.NO_HIT_WITHIN_RANGE)));
        try (var textures = new BlockTextures(new ResourcePack(List.of(folder), 8))) {
            var metadata = JsonParser.parseString(
                            SceneRenderer.render(frame, textures).metadata())
                    .getAsJsonObject();
            assertEquals("[1,2,0]", metadata.get("texture_mask").toString());
            assertEquals("[128,0,0]", metadata.get("texture_alpha").toString());
            assertEquals(1, metadata.get("missing_texture_pixels").getAsInt());
            assertEquals(1, metadata.get("partial_alpha_pixels").getAsInt());
        }
    }

    @Test
    void inheritedVariablesAndAllSixDefaultFaceUvsSelectTheCorrectTexel() throws Exception {
        TextureFixtures.cube(folder);
        try (var textures = new BlockTextures(new ResourcePack(List.of(folder), 8))) {
            assertEquals(
                    0xff030c55,
                    textures.sample(TextureFixtures.hit("minecraft:test", "UP", .2, 1, .8))
                            .argb());
            assertEquals(
                    0xff030355,
                    textures.sample(TextureFixtures.hit("minecraft:test", "DOWN", .2, 0, .8))
                            .argb());
            assertEquals(
                    0xff0c0355,
                    textures.sample(TextureFixtures.hit("minecraft:test", "NORTH", .2, .8, 0))
                            .argb());
            assertEquals(
                    0xff030355,
                    textures.sample(TextureFixtures.hit("minecraft:test", "SOUTH", .2, .8, 1))
                            .argb());
            assertEquals(
                    0xff0c0355,
                    textures.sample(TextureFixtures.hit("minecraft:test", "WEST", 0, .8, .8))
                            .argb());
            assertEquals(
                    0xff030355,
                    textures.sample(TextureFixtures.hit("minecraft:test", "EAST", 1, .8, .8))
                            .argb());
        }
    }

    @Test
    void faceUvRotationAndVariantUvLockPreserveTheSpecifiedMapping() throws Exception {
        TextureFixtures.cube(folder);
        TextureFixtures.json(folder, "models/block/rotated", """
                {"textures":{"all":"block/test"},"elements":[{"from":[0,0,0],"to":[16,16,16],
                "faces":{"up":{"texture":"#all","rotation":90}}}]}
                """);
        TextureFixtures.json(folder, "blockstates/test", """
                {"variants":{"mode=face":{"model":"block/rotated"},
                "mode=unlocked":{"model":"block/test","y":90},
                "mode=locked":{"model":"block/test","y":90,"uvlock":true}}}
                """);
        try (var textures = new BlockTextures(new ResourcePack(List.of(folder), 8))) {
            assertEquals(
                    0xff0c0c55,
                    textures.sample(TextureFixtures.hit("minecraft:test[mode=face]", "UP", .2, 1, .8))
                            .argb());
            assertEquals(
                    0xff0c0c55,
                    textures.sample(TextureFixtures.hit("minecraft:test[mode=unlocked]", "UP", .2, 1, .8))
                            .argb());
            assertEquals(
                    0xff030c55,
                    textures.sample(TextureFixtures.hit("minecraft:test[mode=locked]", "UP", .2, 1, .8))
                            .argb());
        }
    }

    @Test
    void multipartSelectsOnlyMatchingElementsAndCompositesOverlayAlpha() throws Exception {
        TextureFixtures.cube(folder);
        TextureFixtures.texture(folder, "block/red", 0xffff0000);
        TextureFixtures.texture(folder, "block/blue", 0x800000ff);
        TextureFixtures.json(
                folder, "models/block/red", "{\"parent\":\"block/base\",\"textures\":{\"all\":\"block/red\"}}");
        TextureFixtures.json(
                folder, "models/block/blue", "{\"parent\":\"block/base\",\"textures\":{\"all\":\"block/blue\"}}");
        TextureFixtures.json(folder, "blockstates/test", """
                {"multipart":[{"apply":{"model":"block/red"}},{"when":{"OR":[{"on":"true"},{"side":"east"}]},"apply":{"model":"block/blue"}}]}
                """);
        try (var textures = new BlockTextures(new ResourcePack(List.of(folder), 8))) {
            assertEquals(
                    0xffff0000,
                    textures.sample(TextureFixtures.hit("minecraft:test[on=false,side=west]", "UP", .5, 1, .5))
                            .argb());
            assertEquals(
                    0xff7f0080,
                    textures.sample(TextureFixtures.hit("minecraft:test[on=true,side=west]", "UP", .5, 1, .5))
                            .argb());
        }
    }

    @Test
    void halfHeightModelUsesItsOwnUvRectAndUnknownModelsAreExplicit() throws Exception {
        TextureFixtures.cube(folder);
        TextureFixtures.json(folder, "models/block/test", """
                {"textures":{"all":"block/test"},"elements":[{"from":[0,0,0],"to":[16,8,16],
                "faces":{"north":{"texture":"#all","uv":[0,8,16,16]}}}]}
                """);
        try (var textures = new BlockTextures(new ResourcePack(List.of(folder), 8))) {
            assertEquals(
                    0xff080c55,
                    textures.sample(TextureFixtures.hit("minecraft:test", "NORTH", .5, .25, 0))
                            .argb());
            assertFalse(textures.sample(TextureFixtures.hit("minecraft:missing", "UP", .5, 1, .5))
                    .resolved());
        }
    }
}
