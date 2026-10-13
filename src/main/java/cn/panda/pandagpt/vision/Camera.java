package cn.panda.pandagpt.vision;

/** Minecraft yaw/pitch and a perspective projection; FOV is vertical, not horizontal. */
public record Camera(Vec3 origin, double yaw, double pitch, int width, int height, double verticalFov) {
    public record Vec3(double x, double y, double z) {
        public Vec3 add(Vec3 b) { return new Vec3(x + b.x, y + b.y, z + b.z); }
        public Vec3 scale(double n) { return new Vec3(x * n, y * n, z * n); }
        public double dot(Vec3 b) { return x * b.x + y * b.y + z * b.z; }
        public double length() { return Math.sqrt(dot(this)); }
        public Vec3 normalize() { double n = length(); if (n == 0) throw new IllegalArgumentException("Zero direction"); return scale(1 / n); }
        public double distance(Vec3 b) { return add(b.scale(-1)).length(); }
    }
    public Vec3 forward() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }
    public Vec3 ray(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IllegalArgumentException("Pixel outside image");
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        Vec3 right = new Vec3(-Math.cos(yr), 0, -Math.sin(yr));
        Vec3 up = new Vec3(-Math.sin(yr) * Math.sin(pr), Math.cos(pr), Math.cos(yr) * Math.sin(pr));
        double halfY = Math.tan(Math.toRadians(verticalFov) / 2);
        double sx = (2 * (x + .5) / width - 1) * halfY * width / height;
        double sy = (1 - 2 * (y + .5) / height) * halfY;
        return forward().add(right.scale(sx)).add(up.scale(sy)).normalize();
    }
    /** Pixel coordinates in this configured projection; points behind the camera have no projection. */
    public double[] project(Vec3 point) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        Vec3 delta = point.add(origin.scale(-1)); double depth = delta.dot(forward());
        if (depth <= .01) return null;
        Vec3 right = new Vec3(-Math.cos(yr), 0, -Math.sin(yr));
        Vec3 up = new Vec3(-Math.sin(yr) * Math.sin(pr), Math.cos(pr), Math.cos(yr) * Math.sin(pr));
        double halfY = Math.tan(Math.toRadians(verticalFov) / 2);
        return new double[]{(delta.dot(right) / depth / (halfY * width / height) + 1) * width / 2,
                (1 - delta.dot(up) / depth / halfY) * height / 2};
    }
    public boolean matches(Vec3 eye, double currentYaw, double currentPitch, VisionSettings settings) {
        Camera current = new Camera(eye, currentYaw, currentPitch, width, height, verticalFov);
        double angle = Math.toDegrees(Math.acos(Math.clamp(forward().dot(current.forward()), -1, 1)));
        return origin.distance(eye) <= settings.movementTolerance() && angle <= settings.angleTolerance();
    }
}
