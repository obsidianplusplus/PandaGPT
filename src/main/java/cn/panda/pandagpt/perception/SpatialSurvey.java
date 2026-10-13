package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.vision.*;
import cn.panda.pandagpt.vision.Camera.Vec3;
import org.bukkit.World;
import java.util.*;

/** Bounded local collision survey, main-thread cells copied one budget unit at a time. */
public final class SpatialSurvey {
    public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {
        public boolean overlaps(Box b) { return x0 < b.x1 - 1e-6 && x1 > b.x0 + 1e-6 && y0 < b.y1 - 1e-6
                && y1 > b.y0 + 1e-6 && z0 < b.z1 - 1e-6 && z1 > b.z0 + 1e-6; }
    }
    public record Cell(boolean known, String material, List<Box> collisions) {
        public Cell { collisions = List.copyOf(collisions); }
        public static Cell unknown() { return new Cell(false, "UNKNOWN", List.of()); }
    }
    public record Route(int ahead, int lateral, String result, Double floorY, String detail) {}
    public record Enclosure(String status, int connectedAirCells, List<SceneObjects.Pos> boundaries) { public Enclosure { boundaries = List.copyOf(boundaries); } }
    public record Report(List<Route> routes, String scope, Enclosure enclosure) {
        public Report { routes = List.copyOf(routes); }
        public Report(List<Route> routes, String scope) { this(routes, scope, new Enclosure("UNKNOWN", 0, List.of())); }
        public String context() { return "Local collision checks: " + routes + "; local empty-cell connectivity=" + enclosure + "; " + scope; }
    }
    private final Map<SceneObjects.Pos, Cell> cells = new HashMap<>();
    private final World world; private final Set<Long> sent; private final Vec3 feet; private final double yaw;
    private final int cx, cy, cz; private int index;
    public SpatialSurvey(World world, Set<Long> sent, Vec3 feet, double yaw) {
        this.world = world; this.sent = Set.copyOf(sent); this.feet = feet; this.yaw = yaw;
        cx = (int) Math.floor(feet.x()); cy = (int) Math.floor(feet.y()); cz = (int) Math.floor(feet.z());
    }
    public boolean done() { return index >= 343; }
    public boolean contains(SceneObjects.Pos pos) { return cells.containsKey(pos); }
    public void step() {
        if (done()) return;
        int i = index++, x = cx - 3 + i % 7, z = cz - 3 + i / 7 % 7, y = cy - 4 + i / 49;
        SceneObjects.Pos pos = new SceneObjects.Pos(x, y, z);
        if (!sent.contains(BlockRaycaster.chunkKey(x >> 4, z >> 4)) || !world.isChunkLoaded(x >> 4, z >> 4)
                || y < world.getMinHeight() || y >= world.getMaxHeight()) { cells.put(pos, Cell.unknown()); return; }
        var block = world.getBlockAt(x, y, z); List<Box> boxes = new ArrayList<>();
        for (var b : block.getCollisionShape().getBoundingBoxes()) {
            if (boxes.size() >= 64) { cells.put(pos, Cell.unknown()); return; }
            boxes.add(new Box(x + b.getMinX(), y + b.getMinY(), z + b.getMinZ(), x + b.getMaxX(), y + b.getMaxY(), z + b.getMaxZ()));
        }
        cells.put(pos, new Cell(true, block.getType().name(), boxes));
    }
    public Report report() { return analyze(cells, feet, yaw); }
    public static Report analyze(Map<SceneObjects.Pos, Cell> cells, Vec3 feet, double yaw) {
        double angle = Math.toRadians(yaw), fx = -Math.sin(angle), fz = Math.cos(angle), rx = -Math.cos(angle), rz = -Math.sin(angle);
        List<Route> routes = new ArrayList<>();
        for (int distance = 1; distance <= 3; distance++) for (int side = -1; side <= 1; side++) {
            double x = feet.x() + fx * distance + rx * side, z = feet.z() + fz * distance + rz * side;
            routes.add(column(cells, feet.y(), x, z, distance, side));
        }
        return new Report(routes, "Only sampled 7x7 horizontal / 7 vertical cells around feet; 0.6x1.8 standing body, <=1 block steps. "
                + "PASSABLE_LOCAL checks a landing column, not an entire reachable route. Enclosure uses only full collision cubes and empty cells; partial shapes are unknown. "
                + "No pathfinding, jumping, crawling, airtightness or whole-building seal proof. UNKNOWN is not safe.", enclosure(cells, feet));
    }
    private static Enclosure enclosure(Map<SceneObjects.Pos, Cell> cells, Vec3 feet) {
        var start = new SceneObjects.Pos((int) Math.floor(feet.x()), (int) Math.floor(feet.y()), (int) Math.floor(feet.z()));
        Cell first = cells.get(start);
        if (first == null || !first.known || !first.collisions.isEmpty()) return new Enclosure("UNKNOWN_START", 0, List.of());
        var seen = new HashSet<SceneObjects.Pos>(); var queue = new ArrayDeque<SceneObjects.Pos>(); queue.add(start); seen.add(start);
        List<SceneObjects.Pos> boundaries = new ArrayList<>(); boolean open = false, unknown = false;
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        while (!queue.isEmpty()) {
            var p = queue.removeFirst();
            for (int[] d : offsets) {
                var next = new SceneObjects.Pos(p.x() + d[0], p.y() + d[1], p.z() + d[2]);
                if (seen.contains(next)) continue;
                Cell cell = cells.get(next);
                if (cell == null) { open = true; if (boundaries.size() < 8) boundaries.add(p); continue; }
                if (!cell.known || Set.of("WATER", "LAVA", "POWDER_SNOW").contains(cell.material)) { unknown = true; if (boundaries.size() < 8) boundaries.add(next); continue; }
                if (cell.collisions.isEmpty()) { seen.add(next); queue.add(next); }
                else if (cell.collisions.stream().noneMatch(b -> b.x0 <= next.x() && b.x1 >= next.x() + 1 && b.y0 <= next.y()
                        && b.y1 >= next.y() + 1 && b.z0 <= next.z() && b.z1 >= next.z() + 1)) {
                    unknown = true; if (boundaries.size() < 8) boundaries.add(next);
                }
            }
        }
        return new Enclosure(unknown ? "UNKNOWN_BOUNDARY" : open ? "CONNECTED_TO_SURVEY_EDGE"
                : "ENCLOSED_BY_FULL_COLLISIONS_LOCALLY", seen.size(), boundaries);
    }
    private static Route column(Map<SceneObjects.Pos, Cell> cells, double feetY, double x, double z, int ahead, int side) {
        List<Box> shapes = new ArrayList<>(); boolean wet = false;
        for (int bx = (int) Math.floor(x - .3); bx <= (int) Math.floor(x + .3); bx++)
            for (int bz = (int) Math.floor(z - .3); bz <= (int) Math.floor(z + .3); bz++)
                for (int by = (int) Math.floor(feetY) - 4; by <= (int) Math.floor(feetY) + 2; by++) {
                    Cell cell = cells.get(new SceneObjects.Pos(bx, by, bz));
                    if (cell == null || !cell.known) return new Route(ahead, side, "UNKNOWN", null, "Missing/unloaded survey cells");
                    if (by >= Math.floor(feetY) - 1 && by <= Math.floor(feetY) + 1) {
                        if (Set.of("LAVA", "FIRE", "SOUL_FIRE", "CACTUS", "MAGMA_BLOCK", "POWDER_SNOW").contains(cell.material))
                            return new Route(ahead, side, "HAZARD", null, cell.material);
                        wet |= cell.material.equals("WATER");
                    }
                    shapes.addAll(cell.collisions);
                }
        double floor = Double.NEGATIVE_INFINITY;
        for (Box b : shapes) if (b.x0 < x + .3 && b.x1 > x - .3 && b.z0 < z + .3 && b.z1 > z - .3 && b.y1 <= feetY + 1.001)
            floor = Math.max(floor, b.y1);
        if (!Double.isFinite(floor)) return new Route(ahead, side, "NO_FLOOR_IN_SURVEY", null, "No support in the sampled 4-block depth; not a measured infinite cliff");
        if (feetY - floor > 1.001) return new Route(ahead, side, "DROP", floor, "Measured drop=" + Math.round((feetY - floor) * 100) / 100.0);
        Box body = new Box(x - .3, floor + .001, z - .3, x + .3, floor + 1.8, z + .3);
        if (shapes.stream().anyMatch(b -> b.overlaps(body))) return new Route(ahead, side, "BLOCKED", floor, "Standing collision/clearance");
        return new Route(ahead, side, wet ? "WATER_REQUIRES_SWIMMING" : "PASSABLE_LOCAL", floor, "Support and standing clearance verified locally");
    }
}
