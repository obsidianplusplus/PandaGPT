package cn.panda.pandagpt.vision.texture;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaAssetsTest {
    @TempDir
    Path folder;

    @Test
    void onlyConventionallyNamedPacksAreDownloaded() {
        assertEquals(Optional.of("26.3"), VanillaAssets.versionOf(Path.of("assets/vanilla-26.3.zip")));
        assertEquals(Optional.of("26.3-pre1"), VanillaAssets.versionOf(Path.of("vanilla-26.3-pre1.zip")));
        assertTrue(VanillaAssets.versionOf(Path.of("assets/server-pack.zip")).isEmpty());
        assertTrue(VanillaAssets.versionOf(Path.of("assets/vanilla-26.3")).isEmpty());
    }

    @Test
    void extractionKeepsOnlyRenderAssetsAndRecordsProvenance() throws IOException {
        Path client = folder.resolve("client.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(client))) {
            for (int i = 0; i < 120; i++) {
                put(out, "assets/minecraft/textures/block/b" + i + ".png");
            }
            put(out, "assets/minecraft/models/block/stone.json");
            put(out, "net/minecraft/Main.class");
            put(out, "assets/minecraft/sounds/x.ogg");
            put(out, "assets/minecraft/textures/block/../../../evil.png");
        }
        Path output = folder.resolve("vanilla.zip");
        int count = VanillaAssets.extract(client, output, Map.of("version", "26.3"));
        assertEquals(121, count);
        try (var zip = new ZipFile(output.toFile())) {
            assertNull(zip.getEntry("net/minecraft/Main.class"));
            assertNull(zip.getEntry("assets/minecraft/sounds/x.ogg"));
            assertNotNull(zip.getEntry("pandagpt-source.json"));
        }
    }

    @Test
    void clientsWithoutRenderAssetsAreRejected() throws IOException {
        Path client = folder.resolve("client.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(client))) {
            put(out, "net/minecraft/Main.class");
        }
        assertThrows(IOException.class, () -> VanillaAssets.extract(client, folder.resolve("o.zip"), Map.of()));
    }

    private static void put(ZipOutputStream out, String name) throws IOException {
        out.putNextEntry(new ZipEntry(name));
        out.write(new byte[] {1, 2, 3});
        out.closeEntry();
    }
}
