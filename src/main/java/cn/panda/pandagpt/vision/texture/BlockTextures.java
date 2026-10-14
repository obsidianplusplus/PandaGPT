package cn.panda.pandagpt.vision.texture;

import cn.panda.pandagpt.vision.Camera.Vec3;
import cn.panda.pandagpt.vision.RaySample;
import com.google.gson.*;
import java.io.IOException;
import java.util.*;

/** 在服务器碰撞命中点按方块模型采样 PNG 纹理；不是完整的客户端网格渲染器。线程：非线程安全，由 TextureLibrary 串行访问。 */
public final class BlockTextures implements AutoCloseable {
    public record Pixel(int argb, boolean resolved) {}

    private record Model(Map<String, String> textures, List<JsonObject> elements) {}

    private record Variant(String model, int x, int y, boolean uvlock) {}

    private record State(String id, Map<String, String> properties) {}

    private record Quad(
            Vec3 a,
            Vec3 b,
            Vec3 d,
            String face,
            double[] uv,
            int rotation,
            String texture,
            int tint,
            Variant variant) {}

    private record Selection(String state, int x, int y, int z) {}

    private static <K, V> Map<K, V> boundedCache(int limit) {
        return new LinkedHashMap<>(16, .75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > limit;
            }
        };
    }

    private final Map<String, State> states = boundedCache(4096);
    private final Map<Selection, List<Variant>> selections = boundedCache(8192);
    private final ResourcePack resources;
    private final Map<String, Model> models = new HashMap<>();
    private final Map<Variant, List<Quad>> geometry = new HashMap<>();

    public BlockTextures(ResourcePack resources) {
        this.resources = resources;
    }

    public List<String> packNames() {
        return resources.names();
    }

    public Pixel sample(RaySample hit) throws IOException {
        State state = states.computeIfAbsent(hit.blockData(), BlockTextures::state);
        Vec3 point = new Vec3(
                (hit.hitPosition().x() - hit.blockX()) * 16,
                (hit.hitPosition().y() - hit.blockY()) * 16,
                (hit.hitPosition().z() - hit.blockZ()) * 16);
        if (state.id.equals("minecraft:water") || state.id.equals("minecraft:lava")) {
            String texture =
                    state.id.equals("minecraft:water") ? "minecraft:block/water_still" : "minecraft:block/lava_still";
            ResourcePack.Sprite sprite = resources.sprite(texture);
            if (sprite == null) return new Pixel(0, false);
            double[] uv = canonical(hit.face().toLowerCase(Locale.ROOT), point);
            int argb = sprite.sample(uv[0], uv[1]);
            if (state.id.equals("minecraft:water"))
                argb = multiply(argb, waterTint(hit.tint().biome()));
            return new Pixel(argb, true);
        }
        JsonObject blockstate = resources.json(ResourcePack.asset(state.id, "blockstates", ".json"));
        if (blockstate == null) return new Pixel(0, false);
        Selection selection = new Selection(hit.blockData(), hit.blockX(), hit.blockY(), hit.blockZ());
        List<Variant> variants = selections.get(selection);
        if (variants == null) {
            variants = List.copyOf(variants(blockstate, state.properties, hit));
            selections.put(selection, variants);
        }
        int argb = 0;
        boolean resolved = false;
        for (Variant variant : variants) {
            Model model = model(variant.model, new HashSet<>());
            if (model == null) continue;
            List<Quad> faces = geometry.get(variant);
            if (faces == null) {
                if (geometry.size() >= 8192) throw new IOException("Geometry cache budget exceeded");
                faces = quads(model, variant);
                geometry.put(variant, faces);
            }
            // 共面的面（例如草方块侧面与着色覆盖层）按模型顺序叠加。
            for (Quad quad : faces) {
                double[] fractions = projection(quad, point);
                if (fractions == null) continue;
                String texture = reference(quad.texture, model.textures);
                if (texture == null) continue;
                ResourcePack.Sprite sprite = resources.sprite(texture);
                if (sprite == null) continue;
                double u = fractions[0], v = fractions[1];
                double ru = u, rv = v;
                switch (quad.rotation) {
                    case 90 -> {
                        ru = v;
                        rv = 1 - u;
                    }
                    case 180 -> {
                        ru = 1 - u;
                        rv = 1 - v;
                    }
                    case 270 -> {
                        ru = 1 - v;
                        rv = u;
                    }
                    default -> {}
                }
                double tu = quad.uv[0] + (quad.uv[2] - quad.uv[0]) * ru;
                double tv = quad.uv[1] + (quad.uv[3] - quad.uv[1]) * rv;
                if (variant.uvlock) {
                    double[] locked = lockUv(quad.face, variant, tu, tv);
                    tu = locked[0];
                    tv = locked[1];
                }
                int layer = sprite.sample(tu, tv);
                if (quad.tint >= 0) layer = multiply(layer, tint(state.id, hit));
                argb = over(layer, argb);
                resolved = true;
            }
        }
        return new Pixel(argb, resolved);
    }

    private Model model(String id, Set<String> visiting) throws IOException {
        id = qualified(id);
        if (models.containsKey(id)) return models.get(id);
        if (visiting.size() >= 32 || !visiting.add(id)) throw new IOException("Model inheritance cycle/depth exceeded");
        JsonObject json = resources.json(ResourcePack.asset(id, "models", ".json"));
        if (json == null) {
            visiting.remove(id);
            return null;
        }
        Map<String, String> textures = new LinkedHashMap<>();
        List<JsonObject> elements = new ArrayList<>();
        if (json.has("parent")) {
            String parent = json.get("parent").getAsString();
            if (!parent.startsWith("builtin/") && !parent.startsWith("minecraft:builtin/")) {
                Model inherited = model(parent, visiting);
                if (inherited != null) {
                    textures.putAll(inherited.textures);
                    elements.addAll(inherited.elements);
                }
            }
        }
        if (json.has("textures"))
            json.getAsJsonObject("textures")
                    .entrySet()
                    .forEach(e -> textures.put(e.getKey(), e.getValue().getAsString()));
        if (json.has("elements")) {
            elements.clear();
            JsonArray array = json.getAsJsonArray("elements");
            if (array.size() > 256) throw new IOException("Model element budget exceeded");
            for (JsonElement element : array) elements.add(element.getAsJsonObject());
        }
        if (models.size() >= 4096) throw new IOException("Model cache budget exceeded");
        Model result = new Model(Map.copyOf(textures), List.copyOf(elements));
        models.put(id, result);
        visiting.remove(id);
        return result;
    }

    private static String reference(String value, Map<String, String> textures) {
        Set<String> seen = new HashSet<>();
        while (value != null && value.startsWith("#")) {
            if (!seen.add(value) || seen.size() > 32) return null;
            value = textures.get(value.substring(1));
        }
        return value;
    }

    private static List<Variant> variants(JsonObject json, Map<String, String> properties, RaySample hit)
            throws IOException {
        List<Variant> result = new ArrayList<>();
        if (json.has("variants")) {
            for (var entry : json.getAsJsonObject("variants").entrySet()) {
                boolean match = true;
                for (String term : entry.getKey().split(",")) {
                    if (term.isEmpty()) continue;
                    String[] kv = term.split("=", 2);
                    if (kv.length != 2 || !valuesMatch(kv[1], properties.get(kv[0]))) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    result.add(choose(entry.getValue(), hit));
                    break;
                }
            }
        }
        if (json.has("multipart")) {
            if (json.getAsJsonArray("multipart").size() > 256) throw new IOException("Multipart budget exceeded");
            for (JsonElement part : json.getAsJsonArray("multipart")) {
                JsonObject object = part.getAsJsonObject();
                if (!object.has("when") || condition(object.getAsJsonObject("when"), properties, 0))
                    result.add(choose(object.get("apply"), hit));
            }
        }
        return result;
    }

    private static boolean condition(JsonObject condition, Map<String, String> properties, int depth)
            throws IOException {
        if (depth > 16) throw new IOException("Multipart condition depth exceeded");
        for (var e : condition.entrySet()) {
            if (e.getKey().equals("OR") || e.getKey().equals("AND")) {
                boolean all = e.getKey().equals("AND"), value = all;
                for (JsonElement nested : e.getValue().getAsJsonArray()) {
                    boolean current = condition(nested.getAsJsonObject(), properties, depth + 1);
                    value = all ? value && current : value || current;
                }
                if (!value) return false;
            } else if (!valuesMatch(e.getValue().getAsString(), properties.get(e.getKey()))) return false;
        }
        return true;
    }

    private static boolean valuesMatch(String choices, String actual) {
        if (actual == null) return false;
        boolean negate = choices.startsWith("!");
        String value = negate ? choices.substring(1) : choices;
        boolean matched = Arrays.asList(value.split("\\|")).contains(actual);
        return negate != matched;
    }

    private static Variant choose(JsonElement element, RaySample hit) throws IOException {
        JsonObject choice;
        if (element.isJsonArray()) {
            JsonArray options = element.getAsJsonArray();
            if (options.isEmpty() || options.size() > 256) throw new IOException("Invalid weighted variants");
            long total = 0;
            for (JsonElement option : options) {
                int weight = integer(option.getAsJsonObject(), "weight", 1);
                if (weight <= 0 || weight > 1000000) throw new IOException("Invalid variant weight");
                total += weight;
            }
            long seed = hit.blockX() * 3129871L ^ hit.blockZ() * 116129781L ^ hit.blockY();
            long selection = Math.floorMod(seed * seed * 42317861L + seed * 11L, total);
            choice = options.get(0).getAsJsonObject();
            for (JsonElement option : options) {
                choice = option.getAsJsonObject();
                selection -= integer(choice, "weight", 1);
                if (selection < 0) break;
            }
        } else choice = element.getAsJsonObject();
        int x = integer(choice, "x", 0), y = integer(choice, "y", 0);
        if (x % 90 != 0 || y % 90 != 0) throw new IOException("Unsupported variant rotation");
        return new Variant(
                choice.get("model").getAsString(),
                x,
                y,
                choice.has("uvlock") && choice.get("uvlock").getAsBoolean());
    }

    private static List<Quad> quads(Model model, Variant variant) throws IOException {
        List<Quad> result = new ArrayList<>();
        for (JsonObject element : model.elements) {
            Vec3 from = vector(element.getAsJsonArray("from")), to = vector(element.getAsJsonArray("to"));
            if (!element.has("faces")) continue;
            for (var e : element.getAsJsonObject("faces").entrySet()) {
                String face = e.getKey();
                JsonObject data = e.getValue().getAsJsonObject();
                Vec3[] vertices = vertices(face, from, to);
                for (int i = 0; i < vertices.length; i++) {
                    Vec3 point = vertices[i];
                    if (element.has("rotation")) {
                        JsonObject rotation = element.getAsJsonObject("rotation");
                        point = rotate(
                                point,
                                vector(rotation.getAsJsonArray("origin")),
                                rotation.get("axis").getAsString(),
                                rotation.get("angle").getAsDouble(),
                                rotation.has("rescale")
                                        && rotation.get("rescale").getAsBoolean());
                    }
                    vertices[i] = variant(point, variant);
                }
                double[] uv = data.has("uv") ? numbers(data.getAsJsonArray("uv"), 4) : defaultUv(face, from, to);
                int rotation = integer(data, "rotation", 0);
                if (!Set.of(0, 90, 180, 270).contains(rotation)) throw new IOException("Unsupported face UV rotation");
                result.add(new Quad(
                        vertices[0],
                        vertices[1],
                        vertices[2],
                        face,
                        uv,
                        rotation,
                        data.get("texture").getAsString(),
                        integer(data, "tintindex", -1),
                        variant));
            }
        }
        return result;
    }

    private static double[] projection(Quad q, Vec3 point) {
        Vec3 u = q.b.add(q.a.scale(-1)), v = q.d.add(q.a.scale(-1)), w = point.add(q.a.scale(-1));
        double uu = u.dot(u), vv = v.dot(v), uv = u.dot(v), wu = w.dot(u), wv = w.dot(v);
        double denominator = uu * vv - uv * uv;
        if (Math.abs(denominator) < 1e-12) return null;
        double a = (wu * vv - wv * uv) / denominator, b = (wv * uu - wu * uv) / denominator;
        if (a < -1e-5 || b < -1e-5 || a > 1.00001 || b > 1.00001) return null;
        if (q.a.add(u.scale(a)).add(v.scale(b)).distance(point) > .04) return null;
        return new double[] {Math.clamp(a, 0, 1), Math.clamp(b, 0, 1)};
    }

    private static Vec3[] vertices(String face, Vec3 f, Vec3 t) throws IOException {
        return switch (face) {
            case "up" ->
                new Vec3[] {new Vec3(f.x(), t.y(), f.z()), new Vec3(t.x(), t.y(), f.z()), new Vec3(f.x(), t.y(), t.z())
                };
            case "down" ->
                new Vec3[] {new Vec3(f.x(), f.y(), t.z()), new Vec3(t.x(), f.y(), t.z()), new Vec3(f.x(), f.y(), f.z())
                };
            case "north" ->
                new Vec3[] {new Vec3(t.x(), t.y(), f.z()), new Vec3(f.x(), t.y(), f.z()), new Vec3(t.x(), f.y(), f.z())
                };
            case "south" ->
                new Vec3[] {new Vec3(f.x(), t.y(), t.z()), new Vec3(t.x(), t.y(), t.z()), new Vec3(f.x(), f.y(), t.z())
                };
            case "west" ->
                new Vec3[] {new Vec3(f.x(), t.y(), f.z()), new Vec3(f.x(), t.y(), t.z()), new Vec3(f.x(), f.y(), f.z())
                };
            case "east" ->
                new Vec3[] {new Vec3(t.x(), t.y(), t.z()), new Vec3(t.x(), t.y(), f.z()), new Vec3(t.x(), f.y(), t.z())
                };
            default -> throw new IOException("Unknown model face");
        };
    }

    private static double[] defaultUv(String face, Vec3 f, Vec3 t) throws IOException {
        return switch (face) {
            case "up" -> new double[] {f.x(), f.z(), t.x(), t.z()};
            case "down" -> new double[] {f.x(), 16 - t.z(), t.x(), 16 - f.z()};
            case "north" -> new double[] {16 - t.x(), 16 - t.y(), 16 - f.x(), 16 - f.y()};
            case "south" -> new double[] {f.x(), 16 - t.y(), t.x(), 16 - f.y()};
            case "west" -> new double[] {f.z(), 16 - t.y(), t.z(), 16 - f.y()};
            case "east" -> new double[] {16 - t.z(), 16 - t.y(), 16 - f.z(), 16 - f.y()};
            default -> throw new IOException("Unknown UV face");
        };
    }

    private static double[] lockUv(String face, Variant v, double u, double w) throws IOException {
        Vec3[] original = vertices(face, new Vec3(0, 0, 0), new Vec3(16, 16, 16));
        Vec3 a = variant(original[0], v), b = variant(original[1], v), d = variant(original[2], v);
        String worldFace = plane(a, b, d);
        double[] ca = canonical(worldFace, a), cb = canonical(worldFace, b), cd = canonical(worldFace, d);
        return new double[] {
            ca[0] + (cb[0] - ca[0]) * u / 16 + (cd[0] - ca[0]) * w / 16,
            ca[1] + (cb[1] - ca[1]) * u / 16 + (cd[1] - ca[1]) * w / 16
        };
    }

    private static String plane(Vec3 a, Vec3 b, Vec3 d) {
        if (Math.abs(a.y() - b.y()) < 1e-6 && Math.abs(a.y() - d.y()) < 1e-6) return a.y() > 8 ? "up" : "down";
        if (Math.abs(a.x() - b.x()) < 1e-6 && Math.abs(a.x() - d.x()) < 1e-6) return a.x() > 8 ? "east" : "west";
        return a.z() > 8 ? "south" : "north";
    }

    private static double[] canonical(String face, Vec3 p) throws IOException {
        return switch (face) {
            case "up" -> new double[] {p.x(), p.z()};
            case "down" -> new double[] {p.x(), 16 - p.z()};
            case "north" -> new double[] {16 - p.x(), 16 - p.y()};
            case "south" -> new double[] {p.x(), 16 - p.y()};
            case "west" -> new double[] {p.z(), 16 - p.y()};
            case "east" -> new double[] {16 - p.z(), 16 - p.y()};
            default -> throw new IOException("Unknown sample face");
        };
    }

    private static Vec3 variant(Vec3 p, Variant v) throws IOException {
        Vec3 center = new Vec3(8, 8, 8);
        return rotate(rotate(p, center, "x", -v.x, false), center, "y", -v.y, false);
    }

    private static Vec3 rotate(Vec3 point, Vec3 origin, String axis, double degrees, boolean rescale)
            throws IOException {
        if (!Double.isFinite(degrees) || Math.abs(degrees) > 360) throw new IOException("Invalid element rotation");
        Vec3 p = point.add(origin.scale(-1));
        double c = Math.cos(Math.toRadians(degrees)), s = Math.sin(Math.toRadians(degrees));
        double factor = rescale ? 1 / Math.max(.1, Math.abs(c)) : 1;
        Vec3 r = switch (axis) {
            case "x" -> new Vec3(p.x(), (p.y() * c - p.z() * s) * factor, (p.y() * s + p.z() * c) * factor);
            case "y" -> new Vec3((p.x() * c + p.z() * s) * factor, p.y(), (-p.x() * s + p.z() * c) * factor);
            case "z" -> new Vec3((p.x() * c - p.y() * s) * factor, (p.x() * s + p.y() * c) * factor, p.z());
            default -> throw new IOException("Invalid element rotation axis");
        };
        return r.add(origin);
    }

    private int tint(String id, RaySample hit) throws IOException {
        if (id.equals("minecraft:redstone_wire")) {
            double power = Integer.parseInt(state(hit.blockData()).properties.getOrDefault("power", "0")) / 15.0;
            int r = (int) ((power == 0 ? .3 : power * .6 + .4) * 255);
            int g = (int) (Math.max(0, power * power * .7 - .5) * 255);
            int b = (int) (Math.max(0, power * power * .6 - .7) * 255);
            return r << 16 | g << 8 | b;
        }
        if (id.contains("spruce")) return 0x619961;
        if (id.contains("birch")) return 0x80A755;
        String colorMap = id.contains("leaves") || id.contains("vine") ? "foliage" : "grass";
        ResourcePack.Sprite sprite = resources.sprite("minecraft:colormap/" + colorMap);
        if (sprite == null) return hit.rgb();
        double temperature = Math.clamp(hit.tint().temperature(), 0, 1),
                humidity = Math.clamp(hit.tint().humidity(), 0, 1) * temperature;
        return sprite.sample((1 - temperature) * 16, (1 - humidity) * 16) & 0xffffff;
    }

    private static int waterTint(String biome) {
        return switch (biome) {
            case "minecraft:swamp" -> 0x617B64;
            case "minecraft:mangrove_swamp" -> 0x3A7A6A;
            case "minecraft:warm_ocean" -> 0x43D5EE;
            case "minecraft:lukewarm_ocean", "minecraft:deep_lukewarm_ocean" -> 0x45ADF2;
            case "minecraft:cold_ocean", "minecraft:deep_cold_ocean" -> 0x3D57D6;
            default -> 0x3F76E4;
        };
    }

    private static int multiply(int argb, int tint) {
        int r = ((argb >> 16) & 255) * ((tint >> 16) & 255) / 255;
        int g = ((argb >> 8) & 255) * ((tint >> 8) & 255) / 255;
        int b = (argb & 255) * (tint & 255) / 255;
        return (argb & 0xff000000) | (r << 16) | (g << 8) | b;
    }

    private static int over(int source, int destination) {
        double sa = (source >>> 24) / 255.0, da = (destination >>> 24) / 255.0, alpha = sa + da * (1 - sa);
        if (alpha == 0) return 0;
        int rgb = 0;
        for (int shift : new int[] {16, 8, 0}) {
            int channel = (int) Math.round(
                    (((source >> shift) & 255) * sa + ((destination >> shift) & 255) * da * (1 - sa)) / alpha);
            rgb |= channel << shift;
        }
        return ((int) Math.round(alpha * 255) << 24) | rgb;
    }

    private static State state(String text) {
        int bracket = text.indexOf('[');
        String id = bracket < 0 ? text : text.substring(0, bracket);
        Map<String, String> properties = new HashMap<>();
        if (bracket >= 0 && text.endsWith("]"))
            for (String pair : text.substring(bracket + 1, text.length() - 1).split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) properties.put(kv[0], kv[1]);
            }
        return new State(qualified(id), properties);
    }

    private static String qualified(String id) {
        return id.contains(":") ? id : "minecraft:" + id;
    }

    private static int integer(JsonObject object, String key, int fallback) {
        return object.has(key) ? object.get(key).getAsInt() : fallback;
    }

    private static Vec3 vector(JsonArray values) throws IOException {
        double[] n = numbers(values, 3);
        return new Vec3(n[0], n[1], n[2]);
    }

    private static double[] numbers(JsonArray values, int count) throws IOException {
        if (values == null || values.size() != count) throw new IOException("Invalid model vector");
        double[] n = new double[count];
        for (int i = 0; i < count; i++) {
            n[i] = values.get(i).getAsDouble();
            if (!Double.isFinite(n[i]) || Math.abs(n[i]) > 1024) throw new IOException("Invalid model coordinate");
        }
        return n;
    }

    @Override
    public void close() {
        models.clear();
        geometry.clear();
        states.clear();
        selections.clear();
        resources.close();
    }
}
