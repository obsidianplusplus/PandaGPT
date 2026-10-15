package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Native BentoBox island reads, shared by BSkyBlock, AOneBlock and other BentoBox game modes. */
public final class BentoBoxAdapter implements Integration {
    static final int MAX_ISLANDS = 12;
    static final int MAX_SCAN = 128;
    private Plugin installed;
    private Api api;

    public BentoBoxAdapter() {}

    BentoBoxAdapter(Plugin installed, Api api) {
        this.installed = installed;
        this.api = api;
    }

    @Override
    public String id() {
        return "bentobox";
    }

    @Override
    public void validate(Plugin plugin) throws ReflectiveOperationException {
        ClassLoader loader = plugin.getClass().getClassLoader();
        Api verified = new Api(
                Class.forName("world.bentobox.bentobox.BentoBox", false, loader),
                Class.forName("world.bentobox.bentobox.managers.IslandsManager", false, loader),
                Class.forName("world.bentobox.bentobox.managers.IslandWorldManager", false, loader),
                Class.forName("world.bentobox.bentobox.database.objects.Island", false, loader));
        if (!verified.pluginType.isInstance(plugin)) {
            throw new ReflectiveOperationException("BentoBox plugin has an incompatible native API");
        }
        installed = plugin;
        api = verified;
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.readOnlyOf(
                        "bentobox_my_islands",
                        "Read the caller's owned/team BentoBox islands across game modes, with protection range, "
                                + "caller rank and center. Requires each game mode's island.info permission.",
                        object(),
                        this::allowed,
                        null,
                        (context, args) -> mine(context.player())),
                AgentTool.readOnlyOf(
                        "bentobox_island_here",
                        "Read the BentoBox island at the caller's current location. Visitor output excludes "
                                + "island centers, member identities and private team data. Requires the game mode's island.info permission.",
                        object(),
                        this::allowedHere,
                        null,
                        (context, args) -> here(context.player())));
    }

    private boolean allowed(Player player) {
        return installed != null && api != null && installed.isEnabled() && player.hasPermission("pandagpt.use");
    }

    private boolean allowedHere(Player player) {
        if (!allowed(player)) {
            return false;
        }
        try {
            return mayRead(player, api.iwm.invoke(installed), player.getWorld());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    private boolean mayRead(Player player, Object iwm, World world) throws ReflectiveOperationException {
        if (world == null || !(boolean) api.inWorld.invoke(iwm, world)) {
            return false;
        }
        String prefix = (String) api.permissionPrefix.invoke(iwm, world);
        return prefix != null && !prefix.isBlank() && player.hasPermission(prefix + "island.info");
    }

    private ToolResult mine(Player player) {
        if (!allowed(player)) {
            return ToolResult.error("BentoBox is disabled or the player lacks pandagpt.use permission.");
        }
        try {
            Object manager = api.islands.invoke(installed);
            Object iwm = api.iwm.invoke(installed);
            // UUID-scoped cache query; never call getIslands() or getIslands(World), which scan/load every island.
            List<?> islands = (List<?>) api.myIslands.invoke(manager, player.getUniqueId());
            StringBuilder out = new StringBuilder("BentoBox owned/team islands visible to the caller:\n");
            int scanned = 0;
            int shown = 0;
            boolean hidden = false;
            for (Object island : islands) {
                if (scanned++ == MAX_SCAN || shown == MAX_ISLANDS) {
                    out.append("[More islands omitted by scan/output limit.]\n");
                    break;
                }
                if ((boolean) api.deleted.invoke(island)
                        || !(boolean) api.inTeam.invoke(island, player.getUniqueId())) {
                    continue;
                }
                World world = (World) api.world.invoke(island);
                if (!mayRead(player, iwm, world)) {
                    hidden = true;
                    continue;
                }
                out.append(describe(player, island, world, true)).append('\n');
                shown++;
            }
            if (shown == 0) {
                out.append("No owned/team islands are visible with the caller's current permissions.\n");
            }
            if (hidden) {
                out.append(
                        "Some island records were withheld because the game-mode island.info permission is missing.\n");
            }
            return ToolResult.ok(out.toString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ToolResult.error("BentoBox API is unavailable or incompatible; no island data was changed.");
        }
    }

    private ToolResult here(Player player) {
        if (!allowedHere(player)) {
            return ToolResult.error(
                    "This is not a BentoBox world, or the game-mode island.info permission is missing.");
        }
        try {
            Optional<?> found = (Optional<?>) api.islandAt.invoke(api.islands.invoke(installed), player.getLocation());
            if (found.isEmpty() || (boolean) api.deleted.invoke(found.get())) {
                return ToolResult.ok("No active BentoBox island at the player's location.");
            }
            Object island = found.get();
            World world = (World) api.world.invoke(island);
            // Recheck against the island's canonical game-mode world (e.g. callers may be in the Nether).
            if (!mayRead(player, api.iwm.invoke(installed), world)) {
                return ToolResult.error("The player lacks this island's game-mode island.info permission.");
            }
            boolean team = (boolean) api.inTeam.invoke(island, player.getUniqueId());
            return ToolResult.ok(describe(player, island, world, team)
                    + (team ? "" : "\nVisitor view: team details and center coordinates are withheld."));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ToolResult.error("BentoBox API is unavailable or incompatible; no island data was changed.");
        }
    }

    private String describe(Player player, Object island, World world, boolean team)
            throws ReflectiveOperationException {
        String result = "island: " + clean(api.name.invoke(island), 120)
                + ", game mode: " + clean(api.gameMode.invoke(island), 80)
                + ", world: " + clean(world.getName(), 80)
                + ", protection range: " + api.range.invoke(island)
                + ", caller rank: " + api.rank.invoke(island, player.getUniqueId())
                + ", spawn island: " + api.spawn.invoke(island);
        if (team) {
            Location center = (Location) api.center.invoke(island);
            result += ", owner: " + player.getUniqueId().equals(api.owner.invoke(island)) + ", center: "
                    + center.getBlockX() + "," + center.getBlockY() + "," + center.getBlockZ();
        }
        return result;
    }

    private static String clean(Object value, int max) {
        String text = value == null
                ? "unnamed"
                : ChatColor.stripColor(String.valueOf(value)).replaceAll("[\\p{Cntrl}]", " ");
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    /** Resolve the specific native read contract once; incompatible versions do not activate the integration. */
    static final class Api {
        final Class<?> pluginType;
        final Method islands, iwm, myIslands, islandAt, inWorld, permissionPrefix;
        final Method world, name, gameMode, range, rank, spawn, deleted, inTeam, owner, center;

        Api(Class<?> plugin, Class<?> manager, Class<?> worlds, Class<?> island) throws ReflectiveOperationException {
            pluginType = plugin;
            islands = method(plugin, "getIslands", manager);
            iwm = method(plugin, "getIWM", worlds);
            myIslands = method(manager, "getIslands", List.class, UUID.class);
            islandAt = method(manager, "getIslandAt", Optional.class, Location.class);
            inWorld = method(worlds, "inWorld", boolean.class, World.class);
            permissionPrefix = method(worlds, "getPermissionPrefix", String.class, World.class);
            world = method(island, "getWorld", World.class);
            name = method(island, "getName", String.class);
            gameMode = method(island, "getGameMode", String.class);
            range = method(island, "getProtectionRange", int.class);
            rank = method(island, "getRank", int.class, UUID.class);
            spawn = method(island, "isSpawn", boolean.class);
            deleted = method(island, "isDeleted", boolean.class);
            inTeam = method(island, "inTeam", boolean.class, UUID.class);
            owner = method(island, "getOwner", UUID.class);
            center = method(island, "getCenter", Location.class);
        }

        private static Method method(Class<?> type, String name, Class<?> result, Class<?>... args)
                throws ReflectiveOperationException {
            Method method = type.getMethod(name, args);
            if (!result.isAssignableFrom(method.getReturnType())) {
                throw new NoSuchMethodException(type.getName() + "." + name + " has incompatible return type");
            }
            return method;
        }
    }
}
