package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.integer;
import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Reads spawned, visible NPCs in Citizens' default registry; never changes traits or navigation. */
public final class CitizensAdapter implements Integration {
    record Npc(int id, String name) {}

    interface Access {
        boolean ready() throws ReflectiveOperationException;

        Npc npc(Player player, Entity entity) throws ReflectiveOperationException;
    }

    private Access access;

    public CitizensAdapter() {}

    CitizensAdapter(Access access) {
        this.access = access;
    }

    CitizensAdapter(NpcShopReadSupport.ApiClasses classes) throws ReflectiveOperationException {
        access = new NativeAccess(classes);
    }

    @Override
    public String id() {
        return "citizens";
    }

    @Override
    public void validate(Plugin installed) throws ReflectiveOperationException {
        access = new NativeAccess(installed.getClass().getClassLoader());
    }

    private boolean available(Player player) {
        if (NpcShopReadSupport.installed(player, "Citizens") == null || access == null) return false;
        try {
            return access.ready();
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return false;
        }
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(AgentTool.readOnlyOf(
                "citizens_nearby",
                "List up to 12 nearby spawned Citizens NPCs visible to this player, with IDs, names and positions. "
                        + "Uses the default NPC registry and its PlayerFilter visibility checks. "
                        + "No NPC interaction commands or other trait data are exposed.",
                object(integer("radius", "Search radius in blocks, 1–32 (default 16)", false)),
                this::available,
                null,
                (context, args) -> nearby(context.player(), args)));
    }

    private ToolResult nearby(Player player, com.google.gson.JsonObject args) {
        int radius = NpcShopReadSupport.radius(args);
        if (radius < 0) return ToolResult.error("radius must be an integer from 1 to 32.");
        if (!available(player)) return ToolResult.error("Citizens is disabled or its public API is not ready.");
        var origin = player.getLocation();
        record Found(Npc npc, org.bukkit.Location location, double distance) {}
        List<Found> found = new ArrayList<>();
        int scanned = 0;
        boolean capped = false;
        try {
            for (Entity entity : player.getNearbyEntities(radius, radius, radius)) {
                if (scanned++ >= NpcShopReadSupport.MAX_SCANNED_ENTITIES) {
                    capped = true;
                    break;
                }
                if (!NpcShopReadSupport.visible(player, entity, origin, radius)) continue;
                Npc npc = access.npc(player, entity);
                if (npc == null) continue;
                var location = entity.getLocation();
                found.add(new Found(npc, location, Math.sqrt(origin.distanceSquared(location))));
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ex) {
            return ToolResult.error("Citizens NPC query failed; its public API may be incompatible.");
        }
        found.sort(Comparator.comparingDouble(Found::distance)
                .thenComparingInt(row -> row.npc().id()));
        StringBuilder out = new StringBuilder(
                "Visible Citizens NPCs in " + origin.getWorld().getName() + ":\n");
        for (Found row : found.stream().limit(NpcShopReadSupport.MAX_RESULTS).toList()) {
            out.append("#")
                    .append(row.npc().id())
                    .append(" ")
                    .append(NpcShopReadSupport.text(row.npc().name()))
                    .append(" at ")
                    .append(NpcShopReadSupport.position(row.location()))
                    .append(" (")
                    .append(String.format(Locale.ROOT, "%.1f", row.distance()))
                    .append(" blocks)\n");
        }
        if (found.isEmpty()) out.append("No visible spawned NPCs found in the inspected range.\n");
        if (capped) out.append("Entity scan capped at 256; this result may be incomplete.\n");
        if (found.size() > NpcShopReadSupport.MAX_RESULTS)
            out.append("Only the nearest 12 inspected NPCs are shown.\n");
        return ToolResult.ok(out.toString());
    }

    private static final class NativeAccess implements Access {
        private final Method hasImplementation;
        private final Method getRegistry;
        private final Method getNpc;
        private final Method isSpawned;
        private final Method getId;
        private final Method getFullName;
        private final Class<?> playerFilter;
        private final Method getTraitNullable;
        private final Method isHidden;

        NativeAccess(ClassLoader loader) throws ReflectiveOperationException {
            this(name -> NpcShopReadSupport.apiClass(loader, name));
        }

        NativeAccess(NpcShopReadSupport.ApiClasses classes) throws ReflectiveOperationException {
            Class<?> api = classes.resolve("net.citizensnpcs.api.CitizensAPI");
            Class<?> registry = classes.resolve("net.citizensnpcs.api.npc.NPCRegistry");
            Class<?> npc = classes.resolve("net.citizensnpcs.api.npc.NPC");
            Class<?> trait = classes.resolve("net.citizensnpcs.api.trait.Trait");
            playerFilter = classes.resolve("net.citizensnpcs.api.trait.trait.PlayerFilter");
            hasImplementation = NpcShopReadSupport.method(api, "hasImplementation", boolean.class, true);
            getRegistry = NpcShopReadSupport.method(api, "getNPCRegistry", registry, true);
            getNpc = NpcShopReadSupport.method(registry, "getNPC", npc, false, Entity.class);
            isSpawned = NpcShopReadSupport.method(npc, "isSpawned", boolean.class, false);
            getId = NpcShopReadSupport.method(npc, "getId", int.class, false);
            getFullName = NpcShopReadSupport.method(npc, "getFullName", String.class, false);
            getTraitNullable = NpcShopReadSupport.method(npc, "getTraitNullable", trait, false, Class.class);
            isHidden = NpcShopReadSupport.method(playerFilter, "isHidden", boolean.class, false, Player.class);
        }

        public boolean ready() throws ReflectiveOperationException {
            return Boolean.TRUE.equals(hasImplementation.invoke(null)) && getRegistry.invoke(null) != null;
        }

        public Npc npc(Player player, Entity entity) throws ReflectiveOperationException {
            Object registry = getRegistry.invoke(null);
            if (registry == null) return null;
            Object npc = getNpc.invoke(registry, entity);
            if (npc == null || !Boolean.TRUE.equals(isSpawned.invoke(npc))) return null;
            Object filter = getTraitNullable.invoke(npc, playerFilter);
            if (filter != null && Boolean.TRUE.equals(isHidden.invoke(filter, player))) return null;
            return new Npc((int) getId.invoke(npc), (String) getFullName.invoke(npc));
        }
    }
}
