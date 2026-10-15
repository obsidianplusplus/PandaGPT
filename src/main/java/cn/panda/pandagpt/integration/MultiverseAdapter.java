package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import com.google.gson.Gson;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Multiverse-Core 5 public API; reads registered worlds without loading or teleporting to them. */
public final class MultiverseAdapter implements Integration {
    private static final Gson JSON = new Gson();
    private Plugin owner;
    private Api api;

    record Api(
            Method get,
            Method manager,
            Method worlds,
            Method world,
            Method unwrap,
            Method config,
            Method enforceAccess,
            Method locator,
            Method activeService,
            Class<?> checkerType,
            Method canAccess,
            Method name,
            Method alias,
            Method hidden,
            Method loaded,
            Method gameMode,
            Method difficulty,
            Method pvp,
            Method flight,
            Method entryFee,
            Method price,
            Method currency,
            Method spawn,
            Method blacklist) {
        static Api bind(Class<?> core, Class<?> worldType, Class<?> checker) throws ReflectiveOperationException {
            Method get = core.getMethod("get");
            if (!Modifier.isStatic(get.getModifiers()))
                throw new NoSuchMethodException("Multiverse API.get must be static");
            Method manager = VerifiedPublicApi.getter(core, "getWorldManager");
            Class<?> worlds = manager.getReturnType();
            Method world = VerifiedPublicApi.getter(worlds, "getWorld", String.class);
            Method config = VerifiedPublicApi.getter(core, "getCoreConfig");
            Method locator = VerifiedPublicApi.getter(core, "getServiceLocator");
            return new Api(
                    get,
                    manager,
                    VerifiedPublicApi.method(worlds, "getWorlds", Collection.class, false),
                    world,
                    VerifiedPublicApi.getter(world.getReturnType(), "getOrNull"),
                    config,
                    VerifiedPublicApi.method(config.getReturnType(), "getEnforceAccess", boolean.class, false),
                    locator,
                    VerifiedPublicApi.method(
                            locator.getReturnType(),
                            "getActiveService",
                            Object.class,
                            false,
                            Class.class,
                            Annotation[].class),
                    checker,
                    VerifiedPublicApi.method(
                            checker, "hasWorldAccessPermission", boolean.class, false, CommandSender.class, worldType),
                    VerifiedPublicApi.method(worldType, "getName", String.class, false),
                    VerifiedPublicApi.method(worldType, "getAliasOrName", String.class, false),
                    VerifiedPublicApi.method(worldType, "isHidden", boolean.class, false),
                    VerifiedPublicApi.method(worldType, "isLoaded", boolean.class, false),
                    VerifiedPublicApi.method(worldType, "getGameMode", org.bukkit.GameMode.class, false),
                    VerifiedPublicApi.method(worldType, "getDifficulty", org.bukkit.Difficulty.class, false),
                    VerifiedPublicApi.method(worldType, "getPvp", boolean.class, false),
                    VerifiedPublicApi.method(worldType, "isAllowFlight", boolean.class, false),
                    VerifiedPublicApi.method(worldType, "isEntryFeeEnabled", boolean.class, false),
                    VerifiedPublicApi.method(worldType, "getPrice", double.class, false),
                    VerifiedPublicApi.method(worldType, "getCurrency", org.bukkit.Material.class, false),
                    VerifiedPublicApi.method(worldType, "getSpawnLocation", Location.class, false),
                    VerifiedPublicApi.method(worldType, "getWorldBlacklist", List.class, false));
        }
    }

    public MultiverseAdapter() {}

    MultiverseAdapter(Plugin owner, Api api) {
        this.owner = owner;
        this.api = api;
    }

    public String id() {
        return "multiverse";
    }

    @Override
    public void validate(Plugin installed) throws ReflectiveOperationException {
        if (installed == null) throw new ClassNotFoundException("Multiverse-Core is unavailable");
        ClassLoader loader = installed.getClass().getClassLoader();
        String base = "org.mvplugins.multiverse.core.";
        api = Api.bind(
                Class.forName(base + "MultiverseCoreApi", false, loader),
                Class.forName(base + "world.MultiverseWorld", false, loader),
                Class.forName(base + "permissions.CorePermissionsChecker", false, loader));
        owner = installed;
    }

    private static Plugin installed(Player player) {
        return player.getServer().getPluginManager().getPlugin("Multiverse-Core");
    }

    private static boolean allowed(Player player, String permission) {
        Plugin installed = installed(player);
        return installed != null && installed.isEnabled() && player.hasPermission(permission);
    }

    private Api api(Player player) throws ReflectiveOperationException {
        Plugin installed = installed(player);
        if (installed != owner || api == null) validate(installed);
        return api;
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.readOnlyOf(
                        "multiverse_worlds",
                        "Multiverse-Core 5 world catalogue: visible worlds allowed by native access settings and destination-world blacklist. "
                                + "Includes loaded status, game mode, PvP and entry fee; unloaded worlds remain unloaded. This is not a guarantee of teleport access or funds.",
                        object(),
                        p -> allowed(p, "multiverse.core.list.worlds"),
                        null,
                        (context, args) -> read(context.player(), true)),
                AgentTool.readOnlyOf(
                        "multiverse_here",
                        "Multiverse-Core settings for the player's current world: game mode, difficulty, PvP, flight and entry fee. "
                                + "World PvP settings do not override region/plugin restrictions. Never loads worlds or teleports.",
                        object(),
                        p -> allowed(p, "multiverse.core.info"),
                        null,
                        (context, args) -> read(context.player(), false)));
    }

    private ToolResult read(Player player, boolean catalogue) {
        if (!allowed(player, catalogue ? "multiverse.core.list.worlds" : "multiverse.core.info"))
            return ToolResult.error("Multiverse-Core is disabled or this player lacks permission.");
        try {
            Api a = api(player);
            Object core = a.get().invoke(null);
            Object manager = a.manager().invoke(core);
            Object current = a.unwrap()
                    .invoke(a.world().invoke(manager, player.getWorld().getName()));
            if (!catalogue)
                return current == null
                        ? ToolResult.error("Current world is not registered in Multiverse-Core.")
                        : ToolResult.ok(JSON.toJson(describe(a, current)));
            Object value = a.worlds().invoke(manager);
            if (!(value instanceof Collection<?> worlds))
                return ToolResult.error("Unsupported Multiverse world catalogue.");
            boolean enforce =
                    Boolean.TRUE.equals(a.enforceAccess().invoke(a.config().invoke(core)));
            Object checker = enforce
                    ? a.activeService().invoke(a.locator().invoke(core), a.checkerType(), new Annotation[0])
                    : null;
            if (enforce && checker == null)
                return ToolResult.error("Native Multiverse permission checker is unavailable.");
            String from = current == null ? null : identifier(a.name().invoke(current));
            List<Map<String, Object>> results = new ArrayList<>();
            int scanned = 0;
            for (Object world : worlds) {
                if (scanned >= 256 || results.size() >= 32) break;
                scanned++;
                String name = identifier(a.name().invoke(world));
                Object blocked = a.blacklist().invoke(world);
                if (!(blocked instanceof Collection<?> blacklist))
                    return ToolResult.error("Unsupported Multiverse blacklist.");
                // Native WorldEntryChecker tests destination blacklist against the source world.
                if (Boolean.TRUE.equals(a.hidden().invoke(world))
                        || (from != null && blacklist.contains(from))
                        || (enforce && !Boolean.TRUE.equals(a.canAccess().invoke(checker, player, world)))) continue;
                results.add(describe(a, world));
            }
            return ToolResult.ok(JSON.toJson(Map.of(
                    "worlds",
                    results,
                    "scanned",
                    scanned,
                    "partial",
                    scanned < worlds.size(),
                    "access_enforced",
                    enforce)));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ToolResult.error(
                    "Multiverse-Core API is incompatible or not initialized; do not infer world settings.");
        }
    }

    private Map<String, Object> describe(Api a, Object world) throws ReflectiveOperationException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("world", identifier(a.name().invoke(world)));
        out.put(
                "alias",
                cn.panda.pandagpt.util.FormatUtil.truncate(
                        String.valueOf(a.alias().invoke(world)), 160));
        boolean loaded = Boolean.TRUE.equals(a.loaded().invoke(world));
        out.put("loaded", loaded);
        out.put("game_mode", String.valueOf(a.gameMode().invoke(world)));
        out.put("difficulty", String.valueOf(a.difficulty().invoke(world)));
        out.put("pvp", a.pvp().invoke(world));
        out.put("allow_flight", a.flight().invoke(world));
        boolean fee = Boolean.TRUE.equals(a.entryFee().invoke(world));
        out.put("entry_fee_enabled", fee);
        if (fee) {
            out.put("entry_fee", a.price().invoke(world));
            Object currency = a.currency().invoke(world);
            out.put("currency", currency == null ? "economy provider" : currency.toString());
        }
        if (loaded) {
            Object location = a.spawn().invoke(world);
            if (location instanceof Location spawn && spawn.getWorld() != null)
                out.put("spawn", List.of(spawn.getBlockX(), spawn.getBlockY(), spawn.getBlockZ()));
        }
        return out;
    }

    private static String identifier(Object value) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > 128)
            throw new IllegalArgumentException("Unsupported world identifier");
        return text;
    }
}
