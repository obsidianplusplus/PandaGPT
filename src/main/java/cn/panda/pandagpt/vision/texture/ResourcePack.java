package cn.panda.pandagpt.vision.texture;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Read-only folder/ZIP/JAR pack stack. Later entries override earlier entries. No archive is extracted or executed. */
public final class ResourcePack implements AutoCloseable {
    private record Source(Path path, ZipFile archive) {}
    public record Sprite(BufferedImage pixels, int frameX, int frameY, int width, int height) {
        public int sample(double u, double v) {
            int x = Math.clamp((int) Math.floor(u * width / 16), 0, width - 1);
            int y = Math.clamp((int) Math.floor(v * height / 16), 0, height - 1);
            return pixels.getRGB(frameX + x, frameY + y);
        }
    }
    private final List<Source> sources = new ArrayList<>();
    private final Map<String, JsonObject> jsonCache = new HashMap<>();
    private final Map<String, Sprite> sprites = new HashMap<>();
    private final Set<String> missing = new HashSet<>();
    private final long maxPixels;
    private long cachedPixels;

    public ResourcePack(List<Path> paths, int cacheMiB) throws IOException {
        maxPixels = cacheMiB * 1024L * 1024 / 4;
        try {
            for (Path path : paths) {
                Path real = path.toRealPath();
                sources.add(new Source(real, Files.isDirectory(real) ? null : new ZipFile(real.toFile())));
            }
        } catch (IOException e) { close(); throw e; }
    }
    public JsonObject json(String path) throws IOException {
        if (jsonCache.containsKey(path)) return jsonCache.get(path);
        if (missing.contains(path)) return null;
        byte[] bytes = read(path, 1024 * 1024);
        if (bytes == null) { missing.add(path); return null; }
        try {
            JsonObject value = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            if (jsonCache.size() >= 16384) throw new IOException("Resource JSON cache budget exceeded");
            jsonCache.put(path, value); return value;
        } catch (JsonParseException | IllegalStateException e) { throw new IOException("Invalid model JSON: " + path); }
    }
    public Sprite sprite(String resource) throws IOException {
        String path = asset(resource, "textures", ".png");
        if (sprites.containsKey(path)) return sprites.get(path);
        if (missing.contains(path)) return null;
        byte[] bytes = read(path, 4 * 1024 * 1024);
        if (bytes == null) { missing.add(path); return null; }
        BufferedImage image;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("Unsupported image: " + path);
            var reader = readers.next();
            try {
                reader.setInput(input);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                long pixels = (long) width * height;
                if (width > 4096 || height > 16384 || pixels > maxPixels || cachedPixels + pixels > maxPixels)
                    throw new IOException("Texture pixel cache budget exceeded");
                image = reader.read(0); cachedPixels += pixels;
            } finally { reader.dispose(); }
        }
        int frameX = 0, frameY = 0, width = image.getWidth(), height = image.getHeight();
        JsonObject metadata = json(path + ".mcmeta");
        if (metadata != null && metadata.has("animation")) {
            JsonObject animation = metadata.getAsJsonObject("animation");
            int defaultSize = Math.min(width, height);
            width = animation.has("width") ? animation.get("width").getAsInt() : defaultSize;
            height = animation.has("height") ? animation.get("height").getAsInt() : defaultSize;
            if (width <= 0 || height <= 0 || width > image.getWidth() || height > image.getHeight()
                    || image.getWidth() % width != 0 || image.getHeight() % height != 0)
                throw new IOException("Invalid animation frame dimensions");
            int first = 0;
            if (animation.has("frames") && !animation.getAsJsonArray("frames").isEmpty()) {
                JsonElement frame = animation.getAsJsonArray("frames").get(0);
                first = frame.isJsonObject() ? frame.getAsJsonObject().get("index").getAsInt() : frame.getAsInt();
            }
            int columns = image.getWidth() / width, total = columns * (image.getHeight() / height);
            if (first < 0 || first >= total) throw new IOException("Invalid animation frame index");
            frameX = first % columns * width; frameY = first / columns * height;
        }
        Sprite sprite = new Sprite(image, frameX, frameY, width, height);
        sprites.put(path, sprite); return sprite;
    }
    private byte[] read(String name, int maxBytes) throws IOException {
        if (!name.startsWith("assets/") || name.contains("\\") || name.contains("..") || name.startsWith("/"))
            throw new IOException("Invalid resource path");
        for (int i = sources.size() - 1; i >= 0; i--) {
            Source source = sources.get(i);
            if (source.archive != null) {
                ZipEntry entry = source.archive.getEntry(name);
                if (entry == null) continue;
                if (entry.getSize() < 0 || entry.getSize() > maxBytes) throw new IOException("Oversized archive resource");
                try (InputStream input = source.archive.getInputStream(entry)) { return bounded(input, maxBytes); }
            } else {
                Path file = source.path.resolve(name).normalize();
                if (!file.startsWith(source.path) || !Files.isRegularFile(file)) continue;
                if (!file.toRealPath().startsWith(source.path)) throw new IOException("Resource symlink escapes pack folder");
                if (Files.size(file) > maxBytes) throw new IOException("Oversized folder resource");
                try (InputStream input = Files.newInputStream(file)) { return bounded(input, maxBytes); }
            }
        }
        return null;
    }
    private static byte[] bounded(InputStream input, int limit) throws IOException {
        byte[] bytes = input.readNBytes(limit + 1);
        if (bytes.length > limit) throw new IOException("Resource exceeds read budget");
        return bytes;
    }
    public static String asset(String id, String kind, String suffix) throws IOException {
        if (!id.contains(":")) id = "minecraft:" + id;
        String[] split = id.split(":", 2);
        if (!split[0].matches("[a-z0-9_.-]+") || !split[1].matches("[a-z0-9_./-]+") || split[1].contains(".."))
            throw new IOException("Invalid resource identifier");
        return "assets/" + split[0] + "/" + kind + "/" + split[1] + suffix;
    }
    public List<String> names() { return sources.stream().map(s -> s.path.getFileName().toString()).toList(); }
    @Override public void close() {
        for (Source source : sources) if (source.archive != null) try { source.archive.close(); } catch (IOException ignored) {}
        sources.clear(); jsonCache.clear(); sprites.clear(); missing.clear();
    }
}
