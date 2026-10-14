package cn.panda.pandagpt.vision;

/** No live Bukkit objects are retained in a sampled frame. */
public record RaySample(Kind kind, double distance, String material, String blockData, String face,
                        int rgb, int light, Camera.Vec3 hitPosition, int blockX, int blockY, int blockZ, Tint tint) {
    public record Tint(double temperature, double humidity, String biome) {}
    public RaySample(Kind kind, double distance, String material, String blockData, String face,
                     int rgb, int light, Camera.Vec3 hitPosition, int blockX, int blockY, int blockZ) {
        this(kind, distance, material, blockData, face, rgb, light, hitPosition, blockX, blockY, blockZ,
                new Tint(.8, .4, "minecraft:plains"));
    }
    public enum Kind { HIT, NO_HIT_WITHIN_RANGE, UNSENT_OR_UNLOADED }
    public static RaySample empty(Kind kind) {
        return new RaySample(kind, Double.NaN, "", "", "", 0, 15, null, 0, 0, 0);
    }
}
