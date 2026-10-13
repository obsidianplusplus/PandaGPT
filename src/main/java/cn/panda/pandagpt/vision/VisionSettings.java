package cn.panda.pandagpt.vision;

import org.bukkit.configuration.file.FileConfiguration;

public record VisionSettings(boolean enabled, int width, int height, double verticalFov, int maxDistance,
                             int raysPerTick, long nanosPerTick, int maxCaptures, int timeoutTicks,
                             long cooldownMillis, double movementTolerance, double angleTolerance,
                             int maxParticles, boolean saveImages) {
    public static VisionSettings load(FileConfiguration c) {
        int width = number(c, "width", 160, 16, 320), height = number(c, "height", 90, 16, 180);
        return new VisionSettings(c.getBoolean("panda.vision.enabled", true), width, height,
                decimal(c, "vertical_fov", 70, 30, 110), number(c, "max_distance", 96, 8, 128),
                number(c, "rays_per_tick", 256, 16, 2048),
                (long) (decimal(c, "time_budget_ms", 2, .25, 5) * 1_000_000),
                number(c, "max_concurrent_captures", 2, 1, 4), number(c, "timeout_ticks", 200, 20, 600),
                number(c, "cooldown_seconds", 10, 1, 3600) * 1000L,
                decimal(c, "movement_tolerance", .35, 0, 2), decimal(c, "angle_tolerance", 3, .1, 15),
                number(c, "max_debug_particles", 64, 0, 256), c.getBoolean("panda.vision.save_images", true));
    }
    private static int number(FileConfiguration c, String name, int fallback, int min, int max) {
        double value = decimal(c, name, fallback, min, max);
        if (value != Math.rint(value)) throw new IllegalArgumentException("panda.vision." + name + " must be an integer");
        return (int) value;
    }
    private static double decimal(FileConfiguration c, String name, double fallback, double min, double max) {
        String key = "panda.vision." + name;
        Object raw = c.get(key);
        if (raw != null && !(raw instanceof Number)) throw new IllegalArgumentException(key + " must be a number");
        double value = c.getDouble(key, fallback);
        if (!Double.isFinite(value) || value < min || value > max)
            throw new IllegalArgumentException(key + " must be between " + min + " and " + max);
        return value;
    }
}
