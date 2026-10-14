package cn.panda.pandagpt.vision.texture;

import cn.panda.pandagpt.vision.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import javax.imageio.ImageIO;

final class TextureFixtures {
    static void json(Path root, String path, String text) throws IOException {
        Path file = root.resolve("assets/minecraft/" + path + ".json");
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    static void texture(Path root, String name, int rgb) throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) image.setRGB(x, y, rgb);
        png(root, name, image);
    }

    static void png(Path root, String name, BufferedImage image) throws IOException {
        Path file = root.resolve("assets/minecraft/textures/" + name + ".png");
        Files.createDirectories(file.getParent());
        ImageIO.write(image, "png", file.toFile());
    }

    static void cube(Path root) throws IOException {
        json(root, "models/block/base", """
                {"elements":[{"from":[0,0,0],"to":[16,16,16],"faces":{
                "up":{"texture":"#all"},"down":{"texture":"#all"},"north":{"texture":"#all"},
                "south":{"texture":"#all"},"east":{"texture":"#all"},"west":{"texture":"#all"}}}]}
                """);
        json(
                root,
                "models/block/test",
                "{\"parent\":\"block/base\",\"textures\":{\"all\":\"#alias\",\"alias\":\"block/test\"}}");
        json(root, "blockstates/test", "{\"variants\":{\"\":{\"model\":\"block/test\"}}}");
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++) image.setRGB(x, y, 0xff000000 | x << 16 | y << 8 | 0x55);
        png(root, "block/test", image);
    }

    static RaySample hit(String state, String face, double x, double y, double z) {
        return new RaySample(
                RaySample.Kind.HIT, 4, "TEST", state, face, 0xffffff, 15, new Camera.Vec3(x, y, z), 0, 0, 0);
    }
}
