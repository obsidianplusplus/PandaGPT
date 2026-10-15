package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import com.google.gson.Gson;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Jobs Reborn public API: cached current-player professions and a bounded permitted job catalogue. */
public final class JobsAdapter implements Integration {
    private static final Gson JSON = new Gson();
    private static final int MAX_SCAN = 256, MAX_RESULTS = 32;
    private Plugin owner;
    private Api api;

    record Api(
            Method manager,
            Method profile,
            Method progress,
            Method jobs,
            Method job,
            Method name,
            Method display,
            Method level,
            Method xp,
            Method requiredXp) {
        static Api bind(Class<?> jobs, Class<?> progression) throws ReflectiveOperationException {
            Method manager = jobs.getMethod("getPlayerManager");
            Method catalogue = VerifiedPublicApi.method(jobs, "getJobs", Collection.class, true);
            if (!Modifier.isStatic(manager.getModifiers()) || !Modifier.isStatic(catalogue.getModifiers()))
                throw new NoSuchMethodException("Jobs API entry points must be static");
            Method profile = VerifiedPublicApi.getter(manager.getReturnType(), "getJobsPlayer", Player.class);
            Method progress = VerifiedPublicApi.method(profile.getReturnType(), "getJobProgression", List.class, false);
            Method job = VerifiedPublicApi.getter(progression, "getJob");
            Class<?> definition = job.getReturnType();
            return new Api(
                    manager,
                    profile,
                    progress,
                    catalogue,
                    job,
                    VerifiedPublicApi.method(definition, "getName", String.class, false),
                    VerifiedPublicApi.method(definition, "getDisplayName", String.class, false),
                    VerifiedPublicApi.method(progression, "getLevel", int.class, false),
                    VerifiedPublicApi.method(progression, "getExperience", double.class, false),
                    VerifiedPublicApi.method(progression, "getMaxExperience", int.class, false));
        }
    }

    public JobsAdapter() {}

    JobsAdapter(Plugin owner, Api api) {
        this.owner = owner;
        this.api = api;
    }

    public String id() {
        return "jobs";
    }

    @Override
    public void validate(Plugin installed) throws ReflectiveOperationException {
        if (installed == null) throw new ClassNotFoundException("Jobs is unavailable");
        ClassLoader loader = installed.getClass().getClassLoader();
        api = Api.bind(
                Class.forName("com.gamingmesh.jobs.Jobs", false, loader),
                Class.forName("com.gamingmesh.jobs.container.JobProgression", false, loader));
        owner = installed;
    }

    private static Plugin installed(Player player) {
        return player.getServer().getPluginManager().getPlugin("Jobs");
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
                        "jobs_progress",
                        "Current player's loaded Jobs Reborn jobs, levels and experience toward the next level. "
                                + "Uses the cached profile only; never loads other players or awards money/XP.",
                        object(),
                        p -> allowed(p, "jobs.command.stats"),
                        null,
                        (context, args) -> read(context.player(), false)),
                AgentTool.readOnlyOf(
                        "jobs_catalog",
                        "Bounded Jobs Reborn catalogue filtered by this player's jobs.use and jobs.join.<lowercase job> permissions. "
                                + "Join permission is not proof all requirements, limits or cooldowns are met; use server knowledge for payouts. No enrollment occurs.",
                        object(),
                        p -> allowed(p, "jobs.command.browse"),
                        null,
                        (context, args) -> read(context.player(), true)));
    }

    private ToolResult read(Player player, boolean catalogue) {
        if (!allowed(player, catalogue ? "jobs.command.browse" : "jobs.command.stats"))
            return ToolResult.error("Jobs is disabled or this player lacks permission.");
        try {
            Api a = api(player);
            Object source;
            if (catalogue) source = a.jobs().invoke(null);
            else {
                Object manager = a.manager().invoke(null);
                if (manager == null) return ToolResult.error("Jobs player manager is unavailable.");
                Object profile = a.profile().invoke(manager, player);
                if (profile == null)
                    return ToolResult.error("Jobs has not loaded this player's profile; progress is unknown.");
                source = a.progress().invoke(profile);
            }
            if (!(source instanceof Collection<?> entries)) return ToolResult.error("Unsupported Jobs API result.");
            List<Map<String, Object>> results = new ArrayList<>();
            int scanned = 0;
            for (Object entry : entries) {
                if (scanned >= MAX_SCAN || results.size() >= MAX_RESULTS) break;
                scanned++;
                Object job = catalogue ? entry : a.job().invoke(entry);
                String name = identifier(a.name().invoke(job));
                if (catalogue
                        && (!player.hasPermission("jobs.use")
                                || !player.hasPermission("jobs.join." + name.toLowerCase(java.util.Locale.ROOT))))
                    continue;
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("job", name);
                result.put("display_name", text(a.display().invoke(job)));
                if (catalogue) result.put("join_permission", true);
                else {
                    result.put("level", a.level().invoke(entry));
                    result.put("experience", a.xp().invoke(entry));
                    result.put("experience_for_next_level", a.requiredXp().invoke(entry));
                }
                results.add(result);
            }
            return ToolResult.ok(JSON.toJson(Map.of(
                    "jobs",
                    results,
                    "scanned",
                    scanned,
                    "partial",
                    scanned < entries.size(),
                    "scope",
                    "current_player")));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ToolResult.error(
                    "Jobs API is incompatible or player data is unavailable; do not infer job progress.");
        }
    }

    private static String identifier(Object value) {
        if (!(value instanceof String name) || name.isBlank() || name.length() > 128)
            throw new IllegalArgumentException("Unsupported Jobs identifier");
        return name;
    }

    private static String text(Object value) {
        String text = value == null ? "" : value.toString();
        return cn.panda.pandagpt.util.FormatUtil.truncate(text.replaceAll("[\\p{Cntrl}]", " "), 160);
    }
}
