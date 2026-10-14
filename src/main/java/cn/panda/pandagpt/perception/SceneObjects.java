package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.*;
import java.util.*;

/** 把采样像素合并为已观察的表面区域；区域只代表看到的表面，不推断完整物体。纯数据计算，可在任意线程执行。 */
public final class SceneObjects {
    /** 每次观察最多保留的区域编号数。 */
    public static final int MAX_OBJECTS = 48;

    public record Pos(int x, int y, int z) {
        public static Pos of(RaySample p) {
            return new Pos(p.blockX(), p.blockY(), p.blockZ());
        }
    }

    public record Surface(
            int id,
            String material,
            String state,
            int pixels,
            int left,
            int top,
            int right,
            int bottom,
            double screenX,
            double screenY,
            double distance,
            List<Pos> blocks,
            List<Camera.Vec3> visiblePoints) {
        public Surface {
            blocks = List.copyOf(blocks);
            visiblePoints = List.copyOf(visiblePoints);
        }
    }

    public record Analysis(
            List<Surface> objects,
            int[] objectPixels,
            List<String> openingCandidates,
            int openingPixels,
            int unknownPixels) {
        public Analysis {
            objects = List.copyOf(objects);
            objectPixels = objectPixels.clone();
            openingCandidates = List.copyOf(openingCandidates);
        }

        @Override
        public int[] objectPixels() {
            return objectPixels.clone();
        }

        public Surface get(int id) {
            return objects.stream().filter(o -> o.id == id).findFirst().orElse(null);
        }

        public String context() {
            StringBuilder out = new StringBuilder(
                    "Observed surface regions; IDs are capture-local, not complete objects. screenX:0=left,1=right.\n");
            for (Surface s : objects) {
                out.append("#")
                        .append(s.id)
                        .append(' ')
                        .append(s.material)
                        .append(" state=")
                        .append(s.state)
                        .append(" x=")
                        .append(Math.round(s.screenX * 100) / 100.0)
                        .append(" y=")
                        .append(Math.round(s.screenY * 100) / 100.0)
                        .append(" distance=")
                        .append(Math.round(s.distance * 10) / 10.0)
                        .append(" block=")
                        .append(s.blocks.getFirst())
                        .append('\n');
                if (out.length() > 4800) break;
            }
            out.append("Visible opening candidates (not proof that a building is unsealed): ")
                    .append(openingCandidates);
            return out.toString();
        }
    }

    public static Analysis analyze(Camera camera, List<RaySample> pixels) {
        int w = camera.width(), h = camera.height(), size = w * h;
        boolean[] seen = new boolean[size];
        List<List<Integer>> regions = new ArrayList<>();
        for (int seed = 0; seed < size; seed++) {
            if (seen[seed] || pixels.get(seed).kind() != RaySample.Kind.HIT) continue;
            var region = new ArrayList<Integer>();
            var queue = new ArrayDeque<Integer>();
            queue.add(seed);
            seen[seed] = true;
            while (!queue.isEmpty()) {
                int index = queue.removeFirst();
                region.add(index);
                for (int next : neighbors(index, w, h))
                    if (!seen[next] && joins(pixels.get(index), pixels.get(next))) {
                        seen[next] = true;
                        queue.add(next);
                    }
            }
            regions.add(region);
        }
        regions.sort(Comparator.<List<Integer>>comparingInt(
                        r -> interactive(pixels.get(r.getFirst()).material()) ? 0 : 1)
                .thenComparing(
                        Comparator.comparingInt((List<Integer> r) -> r.size()).reversed())
                .thenComparingInt(List::getFirst));
        int[] ids = new int[size];
        List<Surface> surfaces = new ArrayList<>();
        for (List<Integer> region : regions.stream().limit(MAX_OBJECTS).toList()) {
            int id = surfaces.size() + 1, l = w, t = h, r = 0, b = 0;
            double sx = 0, sy = 0, distance = Double.POSITIVE_INFINITY;
            Set<Pos> blocks = new LinkedHashSet<>();
            List<Camera.Vec3> points = new ArrayList<>();
            for (int index : region) {
                ids[index] = id;
                int x = index % w, y = index / w;
                l = Math.min(l, x);
                r = Math.max(r, x);
                t = Math.min(t, y);
                b = Math.max(b, y);
                sx += x + .5;
                sy += y + .5;
                RaySample p = pixels.get(index);
                distance = Math.min(distance, p.distance());
                if (blocks.size() < 256) blocks.add(Pos.of(p));
            }
            // 在已观察到的屏幕轮廓上均匀取点，绝不虚构被遮挡的立方体角点。
            List<Integer> edge = region.stream()
                    .filter(i -> i % w == 0
                            || i % w == w - 1
                            || i / w == 0
                            || i / w == h - 1
                            || Arrays.stream(neighbors(i, w, h)).anyMatch(n -> !joins(pixels.get(i), pixels.get(n))))
                    .toList();
            List<Integer> marks = edge.isEmpty() ? region : edge;
            for (int k = 0; k < Math.min(12, marks.size()); k++)
                points.add(pixels.get(marks.get(k * marks.size() / Math.min(12, marks.size())))
                        .hitPosition());
            RaySample first = pixels.get(region.getFirst());
            surfaces.add(new Surface(
                    id,
                    first.material(),
                    first.blockData(),
                    region.size(),
                    l,
                    t,
                    r,
                    b,
                    sx / region.size() / w,
                    sy / region.size() / h,
                    distance,
                    List.copyOf(blocks),
                    points));
        }
        List<String> openings = new ArrayList<>();
        int openingPixels = 0;
        Arrays.fill(seen, false);
        for (int seed = 0; seed < size && openings.size() < 8; seed++) {
            if (seen[seed] || pixels.get(seed).kind() != RaySample.Kind.NO_HIT_WITHIN_RANGE) continue;
            var queue = new ArrayDeque<Integer>();
            queue.add(seed);
            seen[seed] = true;
            int count = 0, minX = w, minY = h, maxX = 0, maxY = 0, hitEdges = 0;
            boolean touchesEdge = false, unknown = false;
            while (!queue.isEmpty()) {
                int index = queue.removeFirst(), x = index % w, y = index / w;
                count++;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minY = Math.min(minY, y);
                maxY = Math.max(maxY, y);
                touchesEdge |= x == 0 || y == 0 || x == w - 1 || y == h - 1;
                for (int next : neighbors(index, w, h)) {
                    var kind = pixels.get(next).kind();
                    if (kind == RaySample.Kind.HIT) hitEdges++;
                    if (kind == RaySample.Kind.UNSENT_OR_UNLOADED) unknown = true;
                    if (kind == RaySample.Kind.NO_HIT_WITHIN_RANGE && !seen[next]) {
                        seen[next] = true;
                        queue.add(next);
                    }
                }
            }
            if (!touchesEdge && !unknown && count >= 3 && hitEdges >= 4) {
                openingPixels += count;
                openings.add("screen rectangle " + minX + "," + minY + ".." + maxX + "," + maxY
                        + " has no hit within range; needs another view");
            }
        }
        return new Analysis(surfaces, ids, openings, openingPixels, (int) pixels.stream()
                .filter(p -> p.kind() == RaySample.Kind.UNSENT_OR_UNLOADED)
                .count());
    }

    private static boolean joins(RaySample a, RaySample b) {
        if (b.kind() != RaySample.Kind.HIT || !a.material().equals(b.material())) return false;
        Pos p = Pos.of(a), q = Pos.of(b);
        if (interactive(a.material()) && !p.equals(q) && !a.material().endsWith("_DOOR")) return false;
        return Math.abs(p.x - q.x) + Math.abs(p.y - q.y) + Math.abs(p.z - q.z) <= 1;
    }

    public static boolean interactive(String material) {
        return material.contains("FURNACE")
                || material.contains("CHEST")
                || material.endsWith("_DOOR")
                || material.equals("CRAFTING_TABLE")
                || material.equals("BARREL")
                || material.equals("ANVIL")
                || material.equals("SMITHING_TABLE");
    }

    private static int[] neighbors(int i, int w, int h) {
        int x = i % w, y = i / w;
        int[] n = new int[4];
        int s = 0;
        if (x > 0) n[s++] = i - 1;
        if (x + 1 < w) n[s++] = i + 1;
        if (y > 0) n[s++] = i - w;
        if (y + 1 < h) n[s++] = i + w;
        return Arrays.copyOf(n, s);
    }

    private SceneObjects() {}
}
