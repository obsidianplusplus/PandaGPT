package cn.panda.pandagpt.vision;

/** Minecraft 偏航/俯仰角与透视投影；视场角为垂直方向。 */
public record Camera(Vec3 origin, double yaw, double pitch, int width, int height, double verticalFov) {
    public record Vec3(double x, double y, double z) {
        public Vec3 add(Vec3 b) {
            return new Vec3(x + b.x, y + b.y, z + b.z);
        }

        public Vec3 scale(double n) {
            return new Vec3(x * n, y * n, z * n);
        }

        public double dot(Vec3 b) {
            return x * b.x + y * b.y + z * b.z;
        }

        public double length() {
            return Math.sqrt(dot(this));
        }

        public Vec3 normalize() {
            double n = length();
            if (n == 0) throw new IllegalArgumentException("Zero direction");
            return scale(1 / n);
        }

        public double distance(Vec3 b) {
            return add(b.scale(-1)).length();
        }
    }

    public Vec3 forward() {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec3(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    public Vec3 ray(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) throw new IllegalArgumentException("Pixel outside image");
        return projection().ray(x, y);
    }
    /** 每次采样预先计算一次，避免每个像素重复三角运算与创建中间向量。 */
    public Projection projection() {
        return new Projection(this);
    }

    public static final class Projection {
        private final Vec3 forward, right, up;
        private final int width, height;
        private final double halfY;

        private Projection(Camera camera) {
            double yr = Math.toRadians(camera.yaw()), pr = Math.toRadians(camera.pitch());
            forward = camera.forward();
            right = new Vec3(-Math.cos(yr), 0, -Math.sin(yr));
            up = new Vec3(-Math.sin(yr) * Math.sin(pr), Math.cos(pr), Math.cos(yr) * Math.sin(pr));
            width = camera.width();
            height = camera.height();
            halfY = Math.tan(Math.toRadians(camera.verticalFov()) / 2);
        }

        public Vec3 ray(int x, int y) {
            if (x < 0 || x >= width || y < 0 || y >= height) throw new IllegalArgumentException("Pixel outside image");
            double sx = (2 * (x + .5) / width - 1) * halfY * width / height;
            double sy = (1 - 2 * (y + .5) / height) * halfY;
            return new Vec3(
                            forward.x() + right.x() * sx + up.x() * sy,
                            forward.y() + up.y() * sy,
                            forward.z() + right.z() * sx + up.z() * sy)
                    .normalize();
        }
    }
    /** 返回该点在当前投影下的像素坐标；位于相机后方的点返回 null。 */
    public double[] project(Vec3 point) {
        double yr = Math.toRadians(yaw), pr = Math.toRadians(pitch);
        Vec3 delta = point.add(origin.scale(-1));
        double depth = delta.dot(forward());
        if (depth <= .01) return null;
        Vec3 right = new Vec3(-Math.cos(yr), 0, -Math.sin(yr));
        Vec3 up = new Vec3(-Math.sin(yr) * Math.sin(pr), Math.cos(pr), Math.cos(yr) * Math.sin(pr));
        double halfY = Math.tan(Math.toRadians(verticalFov) / 2);
        return new double[] {
            (delta.dot(right) / depth / (halfY * width / height) + 1) * width / 2,
            (1 - delta.dot(up) / depth / halfY) * height / 2
        };
    }

    public boolean matches(Vec3 eye, double currentYaw, double currentPitch, VisionSettings settings) {
        Camera current = new Camera(eye, currentYaw, currentPitch, width, height, verticalFov);
        double angle = Math.toDegrees(Math.acos(Math.clamp(forward().dot(current.forward()), -1, 1)));
        return origin.distance(eye) <= settings.movementTolerance() && angle <= settings.angleTolerance();
    }
}
