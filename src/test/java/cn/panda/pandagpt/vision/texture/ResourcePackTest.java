package cn.panda.pandagpt.vision.texture;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ResourcePackTest {
    @TempDir
    Path folder;

    @Test
    void laterPacksOverridePngFilesAndZipPacksAreReadable() throws Exception {
        Path base = folder.resolve("base"), override = folder.resolve("override");
        TextureFixtures.texture(base, "block/test", 0xff112233);
        TextureFixtures.texture(override, "block/test", 0xffaabbcc);
        try (var pack = new ResourcePack(List.of(base, override), 8)) {
            assertEquals(0xffaabbcc, pack.sprite("block/test").sample(8, 8));
        }
        Path zip = folder.resolve("pack.zip");
        try (var output = new ZipOutputStream(Files.newOutputStream(zip))) {
            output.putNextEntry(new ZipEntry("assets/minecraft/textures/block/test.png"));
            output.write(Files.readAllBytes(base.resolve("assets/minecraft/textures/block/test.png")));
            output.closeEntry();
        }
        try (var pack = new ResourcePack(List.of(zip), 8)) {
            assertEquals(0xff112233, pack.sprite("block/test").sample(8, 8));
        }
    }

    @Test
    void animationUsesTheFirstDeclaredFrame() throws Exception {
        BufferedImage image = new BufferedImage(16, 32, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 32; y++) image.setRGB(x, y, y < 16 ? 0xffff0000 : 0xff00ff00);
        TextureFixtures.png(folder, "block/test", image);
        Files.writeString(
                folder.resolve("assets/minecraft/textures/block/test.png.mcmeta"),
                "{\"animation\":{\"frames\":[1,0]}}");
        try (var pack = new ResourcePack(List.of(folder), 8)) {
            var sprite = pack.sprite("block/test");
            assertEquals(16, sprite.height());
            assertEquals(0xff00ff00, sprite.sample(8, 8));
        }
    }

    @Test
    void resourceIdentifiersCannotEscapeThePack() throws Exception {
        try (var pack = new ResourcePack(List.of(folder), 8)) {
            assertThrows(IOException.class, () -> pack.sprite("minecraft:../../secret"));
            assertThrows(IOException.class, () -> pack.json("../config.yml"));
            assertNull(pack.sprite("minecraft:block/missing"));
        }
    }
}
