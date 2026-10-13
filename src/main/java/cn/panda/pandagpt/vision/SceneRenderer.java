package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.ai.AiRequest;
import cn.panda.pandagpt.vision.texture.BlockTextures;
import com.google.gson.Gson;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;

/** Deterministic data rendering, not AI-generated imagery or a claimed client screenshot. */
public final class SceneRenderer {
    public record Frame(UUID player, UUID captureId, String world, Camera camera, int range,
                        long startedAt, long completedAt, List<RaySample> pixels) {
        public Frame {
            pixels = List.copyOf(pixels);
            if (pixels.size() != camera.width() * camera.height()) throw new IllegalArgumentException("Incomplete frame");
        }
    }
    public record Rendered(byte[] colorPng, byte[] depthPng, byte[] labelsPng, String metadata, String summary) {
        public AiRequest.Image image() { return new AiRequest.Image("image/png", Base64.getEncoder().encodeToString(colorPng)); }
    }
    public static Rendered render(Frame frame) throws IOException {
        return render(frame, null);
    }
    public static Rendered render(Frame frame, BlockTextures textures) throws IOException {
        int width = frame.camera.width(), height = frame.camera.height();
        BufferedImage color = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage depth = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        BufferedImage labels = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Map<String, Integer> ids = new LinkedHashMap<>(), counts = new TreeMap<>();
        Map<String, String> palette = new LinkedHashMap<>();
        List<Integer> materialIds = new ArrayList<>(), mask = new ArrayList<>();
        List<Double> distances = new ArrayList<>();
        List<Integer> textureMask = new ArrayList<>(), alphaMask = new ArrayList<>();
        int unknown = 0, miss = 0, missingTextures = 0, transparent = 0;
        for (int index = 0; index < frame.pixels.size(); index++) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Rendering cancelled");
            int x = index % width, y = index / width;
            RaySample p = frame.pixels.get(index);
            int rgb, gray = 0, id = 0, textureStatus = 0, alpha = 0;
            if (p.kind() == RaySample.Kind.HIT) {
                id = ids.computeIfAbsent(p.material(), name -> ids.size() + 1);
                counts.merge(p.material(), 1, Integer::sum);
                double faceShade = switch (p.face()) { case "UP" -> 1; case "DOWN" -> .55; case "NORTH", "SOUTH" -> .8; default -> .7; };
                double light = .25 + .75 * Math.clamp(p.light(), 0, 15) / 15.0;
                int surfaceRgb = p.rgb();
                if (textures != null) {
                    BlockTextures.Pixel texel = textures.sample(p);
                    if (texel.resolved()) {
                        textureStatus = 1; alpha = texel.argb() >>> 24;
                        if (alpha < 255) transparent++;
                        int backdrop = ((x / 4 + y / 4) % 2 == 0) ? 0x777777 : 0x444444;
                        surfaceRgb = composite(texel.argb(), backdrop);
                    } else {
                        textureStatus = 2; missingTextures++;
                        surfaceRgb = ((x / 4 + y / 4) % 2 == 0) ? 0xff00ff : 0x222222;
                    }
                }
                rgb = scale(surfaceRgb, faceShade * light);
                gray = (int) Math.round(255 * (1 - Math.min(p.distance(), frame.range) / frame.range));
                distances.add(Math.round(p.distance() * 1000) / 1000.0);
            } else {
                boolean unavailable = p.kind() == RaySample.Kind.UNSENT_OR_UNLOADED;
                if (unavailable) unknown++; else miss++;
                // The blue background is only a display convention, never evidence of real sky.
                rgb = unavailable ? (((x / 4 + y / 4) % 2 == 0) ? 0x777777 : 0x444444) : 0x92B9DA;
                distances.add(null);
            }
            color.setRGB(x, y, rgb); depth.setRGB(x, y, gray * 0x010101);
            int labelColor = (id * 0x9E3779) & 0xFFFFFF;
            if (id != 0) palette.put(p.material(), String.format("#%06X", labelColor));
            labels.setRGB(x, y, labelColor); materialIds.add(id); mask.add(p.kind().ordinal());
            textureMask.add(textureStatus); alphaMask.add(alpha);
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("capture_id", frame.captureId); metadata.put("player_uuid", frame.player);
        metadata.put("world", frame.world); metadata.put("camera", frame.camera);
        metadata.put("range_blocks", frame.range); metadata.put("started_at_ms", frame.startedAt);
        metadata.put("completed_at_ms", frame.completedAt); metadata.put("pixel_order", "row-major, top-left origin");
        metadata.put("material_ids", ids); metadata.put("material_id_pixels", materialIds);
        metadata.put("labels_palette", palette);
        metadata.put("texture_mode", textures == null ? "map_colors" : "resource_pack_png");
        metadata.put("resource_packs", textures == null ? List.of() : textures.packNames());
        metadata.put("texture_mask_codes", Map.of("NOT_TEXTURED", 0, "PNG_RESOLVED", 1, "MISSING_MODEL_OR_TEXTURE", 2));
        metadata.put("texture_mask", textureMask); metadata.put("texture_alpha", alphaMask);
        metadata.put("missing_texture_pixels", missingTextures); metadata.put("partial_alpha_pixels", transparent);
        metadata.put("mask_codes", Map.of("HIT", 0, "NO_HIT_WITHIN_RANGE", 1, "UNSENT_OR_UNLOADED", 2));
        metadata.put("mask", mask); metadata.put("distance_blocks", distances);
        metadata.put("depth_png", "255=near, 0=range or invalid; consult mask. Values are ray distance, not camera Z.");
        metadata.put("labels_png", "Class colors are in labels_palette. Black means no hit; consult mask for inaccessible pixels.");
        metadata.put("limitations", "Server first-person reconstruction using collision hits and "
                + (textures == null ? "map colors. " : "configured resource-pack PNGs/model UVs. ")
                + "Not a client screenshot. Configured vertical FOV/aspect, no HUD/entities; "
                + "transparent texels show an unknown checkerboard, not the scene behind them. "
                + "Weighted variants are coordinate-deterministic, animation uses first declared frame, and biome tints/lighting are approximate. "
                + "Blue means no hit within range, not verified sky. "
                + "Sent chunk keys do not prove client has finished rendering. World may change during multi-tick capture.");
        String summary = "Raycast reconstruction, NOT client screenshot. width=" + width + ", height=" + height
                + ", vertical_fov=" + frame.camera.verticalFov() + ", range=" + frame.range
                + ", unavailable_pixels=" + unknown + ", no_hit_within_range=" + miss
                + ", top visible materials=" + counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(12).toList()
                + (textures == null ? ". Flat map colors." : ". Real resource-pack PNG textures, missing_texture_pixels=" + missingTextures
                + ", partially_transparent_pixels=" + transparent + ".")
                + " No HUD/entities; configured pack may differ from the player's pack. Transparent texels show unknown checkerboard, not behind-block data. "
                + "Grey checkerboard is inaccessible data. Blue is a no-hit placeholder, NOT proof of sky.";
        return new Rendered(png(color), png(depth), png(labels), new Gson().toJson(metadata), summary);
    }
    private static int scale(int rgb, double factor) {
        int r = (int) (((rgb >> 16) & 255) * factor), g = (int) (((rgb >> 8) & 255) * factor), b = (int) ((rgb & 255) * factor);
        return (r << 16) | (g << 8) | b;
    }
    private static int composite(int argb, int backdrop) {
        int alpha = argb >>> 24, rgb = 0;
        for (int shift : new int[]{16, 8, 0})
            rgb |= ((((argb >> shift) & 255) * alpha + ((backdrop >> shift) & 255) * (255 - alpha)) / 255) << shift;
        return rgb;
    }
    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
        return output.toByteArray();
    }
    private SceneRenderer() {}
}
