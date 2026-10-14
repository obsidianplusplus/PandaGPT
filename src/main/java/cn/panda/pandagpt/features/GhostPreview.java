package cn.panda.pandagpt.features;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 建筑幽灵预览：用只发给该玩家的假方块显示 Agent 设计的结构，不会改动真实世界。
 *
 * <p>坐标使用玩家视角的局部坐标系：x 向右、y 向上、z 向前，原点在玩家前方或脚下，朝向取最接近的水平方向。
 * 线程：仅限服务器主线程。
 */
public final class GhostPreview {
    /**
     * 结构的一个组成部分。
     *
     * @param shape block、fill、hollow、walls、line、cylinder、sphere、pyramid 之一
     * @param from 局部坐标起点；圆柱、球、金字塔为中心或底面中心
     * @param to 局部坐标终点（fill/hollow/walls/line 使用）
     */
    public record Part(String shape, Material material, int[] from, int[] to, int radius, int height, boolean hollow) {}

    /** 一次预览的结果，交给 Agent 转述。 */
    public record Result(
            int shown, int skipped, boolean truncated, Map<String, Integer> needed, Map<String, Integer> missing) {}

    private record Shown(UUID world, List<Location> locations, long expires) {}

    // 局部坐标的绝对值上限，防止预览跑到很远的区块。
    private static final int MAX_OFFSET = 48;

    private final Map<UUID, Shown> shown = new HashMap<>();
    // 方块数据工厂；测试中可替换，避免依赖服务器注册表。
    private final java.util.function.Function<Material, org.bukkit.block.data.BlockData> blockData;

    public GhostPreview() {
        this(Material::createBlockData);
    }

    public GhostPreview(java.util.function.Function<Material, org.bukkit.block.data.BlockData> blockData) {
        this.blockData = blockData;
    }

    /** 显示预览；同一玩家的旧预览会先被清除。 */
    public Result show(Player player, List<Part> parts, boolean atFeet, int maxBlocks, long expires) {
        clear(player);
        var base = player.getLocation();
        double yaw = Math.toRadians(Math.round(base.getYaw() / 90.0) * 90.0);
        // 与 Minecraft 的偏航角一致：0 朝南（+Z），向右为 -X。
        int fx = (int) Math.round(-Math.sin(yaw));
        int fz = (int) Math.round(Math.cos(yaw));
        int rx = -fz;
        int rz = fx;
        int ox = base.getBlockX() + (atFeet ? 0 : fx * 2);
        int oy = base.getBlockY();
        int oz = base.getBlockZ() + (atFeet ? 0 : fz * 2);
        Map<Long, Material> blocks = new LinkedHashMap<>();
        boolean truncated = false;
        for (Part part : parts) {
            for (int[] local : shape(part, maxBlocks * 4)) {
                if (Math.abs(local[0]) > MAX_OFFSET
                        || Math.abs(local[1]) > MAX_OFFSET
                        || Math.abs(local[2]) > MAX_OFFSET) {
                    continue;
                }
                int x = ox + local[0] * rx + local[2] * fx;
                int y = oy + local[1];
                int z = oz + local[0] * rz + local[2] * fz;
                if (blocks.size() >= maxBlocks && !blocks.containsKey(key(x, y, z))) {
                    truncated = true;
                    continue;
                }
                blocks.put(key(x, y, z), part.material());
            }
        }
        World world = player.getWorld();
        List<Location> locations = new ArrayList<>();
        Map<String, Integer> needed = new TreeMap<>();
        int skipped = 0;
        for (var entry : blocks.entrySet()) {
            int x = (int) (entry.getKey() >> 38);
            int y = (int) (entry.getKey() << 52 >> 52);
            int z = (int) (entry.getKey() << 26 >> 38);
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
                skipped++;
                continue;
            }
            Location location = new Location(world, x, y, z);
            player.sendBlockChange(location, blockData.apply(entry.getValue()));
            locations.add(location);
            if (entry.getValue() != Material.AIR) {
                needed.merge(entry.getValue().name(), 1, Integer::sum);
            }
        }
        shown.put(player.getUniqueId(), new Shown(world.getUID(), locations, expires));
        Map<String, Integer> owned = new HashMap<>();
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null) {
                owned.merge(item.getType().name(), item.getAmount(), Integer::sum);
            }
        }
        Map<String, Integer> missing = new TreeMap<>();
        needed.forEach((material, count) -> {
            int lack = count - owned.getOrDefault(material, 0);
            if (lack > 0) {
                missing.put(material, lack);
            }
        });
        return new Result(locations.size(), skipped, truncated, needed, missing);
    }

    /** 恢复玩家看到的真实方块。 */
    public boolean clear(Player player) {
        Shown previous = shown.remove(player.getUniqueId());
        if (previous == null) {
            return false;
        }
        if (player.getWorld().getUID().equals(previous.world())) {
            for (Location location : previous.locations()) {
                if (location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    player.sendBlockChange(location, location.getBlock().getBlockData());
                }
            }
        }
        return true;
    }

    public void forget(UUID player) {
        shown.remove(player);
    }

    /** 到期的预览自动恢复。 */
    public void tick(java.util.function.Function<UUID, Player> players, long now) {
        for (UUID id : List.copyOf(shown.keySet())) {
            if (shown.get(id).expires() <= now) {
                Player player = players.apply(id);
                if (player != null) {
                    clear(player);
                } else {
                    shown.remove(id);
                }
            }
        }
    }

    public int active() {
        return shown.size();
    }

    // 生成某个部件覆盖的局部坐标；超过 budget 个点后停止，防止巨大的形状拖慢主线程。
    public static List<int[]> shape(Part part, int budget) {
        List<int[]> out = new ArrayList<>();
        int[] a = part.from();
        int[] b = part.to() == null ? part.from() : part.to();
        int x0 = Math.min(a[0], b[0]), x1 = Math.max(a[0], b[0]);
        int y0 = Math.min(a[1], b[1]), y1 = Math.max(a[1], b[1]);
        int z0 = Math.min(a[2], b[2]), z1 = Math.max(a[2], b[2]);
        int r = Math.max(0, Math.min(part.radius(), MAX_OFFSET));
        int h = Math.max(1, Math.min(part.height(), MAX_OFFSET));
        switch (part.shape().toLowerCase(Locale.ROOT)) {
            case "block" -> out.add(a.clone());
            case "fill", "hollow", "walls" -> {
                boolean hollow = part.shape().equalsIgnoreCase("hollow");
                boolean walls = part.shape().equalsIgnoreCase("walls");
                for (int x = x0; x <= x1 && out.size() < budget; x++) {
                    for (int y = y0; y <= y1 && out.size() < budget; y++) {
                        for (int z = z0; z <= z1 && out.size() < budget; z++) {
                            boolean side = x == x0 || x == x1 || z == z0 || z == z1;
                            boolean cap = y == y0 || y == y1;
                            if ((hollow && !(side || cap)) || (walls && !side)) {
                                continue;
                            }
                            out.add(new int[] {x, y, z});
                        }
                    }
                }
            }
            case "line" -> {
                int steps = Math.max(Math.max(Math.abs(b[0] - a[0]), Math.abs(b[1] - a[1])), Math.abs(b[2] - a[2]));
                for (int i = 0; i <= steps && out.size() < budget; i++) {
                    double t = steps == 0 ? 0 : (double) i / steps;
                    out.add(new int[] {
                        (int) Math.round(a[0] + (b[0] - a[0]) * t),
                        (int) Math.round(a[1] + (b[1] - a[1]) * t),
                        (int) Math.round(a[2] + (b[2] - a[2]) * t)
                    });
                }
            }
            case "cylinder" -> {
                for (int y = 0; y < h && out.size() < budget; y++) {
                    for (int x = -r; x <= r; x++) {
                        for (int z = -r; z <= r; z++) {
                            double d = Math.sqrt(x * x + z * z);
                            if (d <= r + .5 && (!part.hollow() || d >= r - .5)) {
                                out.add(new int[] {a[0] + x, a[1] + y, a[2] + z});
                            }
                        }
                    }
                }
            }
            case "sphere" -> {
                for (int x = -r; x <= r && out.size() < budget; x++) {
                    for (int y = -r; y <= r; y++) {
                        for (int z = -r; z <= r; z++) {
                            double d = Math.sqrt(x * x + y * y + z * z);
                            if (d <= r + .5 && (!part.hollow() || d >= r - .5)) {
                                out.add(new int[] {a[0] + x, a[1] + y, a[2] + z});
                            }
                        }
                    }
                }
            }
            case "pyramid" -> {
                for (int level = 0; level <= r && out.size() < budget; level++) {
                    int half = r - level;
                    for (int x = -half; x <= half; x++) {
                        for (int z = -half; z <= half; z++) {
                            boolean edge = Math.abs(x) == half || Math.abs(z) == half;
                            if (!part.hollow() || edge || level == 0) {
                                out.add(new int[] {a[0] + x, a[1] + level, a[2] + z});
                            }
                        }
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unknown shape " + part.shape());
        }
        return out;
    }

    // 把方块坐标压缩为一个 long：x、z 各 26 位，y 12 位。
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
