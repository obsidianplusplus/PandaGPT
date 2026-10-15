package cn.panda.pandagpt.integration;

import cn.panda.pandagpt.agent.Schema;
import com.google.gson.JsonObject;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Bounded, public-API-only helpers for the NPC and shop readers. */
final class NpcShopReadSupport {
    interface ApiClasses {
        Class<?> resolve(String name) throws ClassNotFoundException;
    }

    static final int MAX_SCANNED_ENTITIES = 256;
    static final int MAX_RESULTS = 12;

    static int radius(JsonObject args) {
        int radius = Schema.number(args, "radius", 16);
        return radius >= 1 && radius <= 32 ? radius : -1;
    }

    static Plugin installed(Player player, String name) {
        var manager = player.getServer().getPluginManager();
        return manager.isPluginEnabled(name) ? manager.getPlugin(name) : null;
    }

    static boolean visible(Player player, Entity entity, Location origin, int radius) {
        Location location = entity.getLocation();
        return entity.isValid()
                && origin.getWorld().equals(location.getWorld())
                && origin.distanceSquared(location) <= radius * radius
                && player.canSee(entity)
                && player.hasLineOfSight(entity);
    }

    static String text(String value) {
        if (value == null || value.isBlank()) return "(unnamed)";
        String plain =
                ChatColor.stripColor(value).replaceAll("[\\p{Cntrl}]", " ").strip();
        return plain.length() > 80 ? plain.substring(0, 80) + "…" : plain;
    }

    static String position(Location location) {
        return location.getBlockX() + "," + location.getBlockY() + "," + location.getBlockZ();
    }

    static Class<?> apiClass(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    static Method method(Class<?> type, String name, Class<?> returns, boolean isStatic, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getMethod(name, parameters);
        if (!returns.isAssignableFrom(method.getReturnType()) || Modifier.isStatic(method.getModifiers()) != isStatic) {
            throw new NoSuchMethodException(type.getName() + "." + name + " has an incompatible signature");
        }
        return method;
    }

    private NpcShopReadSupport() {}
}
