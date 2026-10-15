package cn.panda.pandagpt.integration;

import static cn.panda.pandagpt.agent.Schema.object;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.ToolResult;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** PikaMug Quests 5.x: reads existing records without creating questers or initializing progress. */
public final class QuestsAdapter implements Integration {
    static final int MAX_QUESTERS = 2048;
    static final int MAX_QUESTS = 10;
    static final int MAX_OBJECTIVES = 24;
    static final int MAX_OUTPUT = 8192;
    private Plugin installed;
    private Api api;

    public QuestsAdapter() {}

    QuestsAdapter(Plugin installed, Api api) {
        this.installed = installed;
        this.api = api;
    }

    @Override
    public String id() {
        return "quests";
    }

    @Override
    public void validate(Plugin plugin) throws ReflectiveOperationException {
        ClassLoader loader = plugin.getClass().getClassLoader();
        Api verified = new Api(
                Class.forName("me.pikamug.quests.Quests", false, loader),
                Class.forName("me.pikamug.quests.player.Quester", false, loader),
                Class.forName("me.pikamug.quests.player.BukkitQuester", false, loader),
                Class.forName("me.pikamug.quests.quests.Quest", false, loader),
                Class.forName("me.pikamug.quests.quests.components.Stage", false, loader),
                Class.forName("me.pikamug.quests.player.QuestProgress", false, loader),
                Class.forName("me.pikamug.quests.util.stack.BlockItemStack", false, loader),
                Class.forName("me.pikamug.quests.module.CustomObjective", false, loader));
        if (!verified.questsType.isInstance(plugin)) {
            throw new ReflectiveOperationException("Quests plugin does not implement the PikaMug Quests 5 API");
        }
        installed = plugin;
        api = verified;
    }

    @Override
    public List<AgentTool> tools(ChatGPTIntegration plugin) {
        return List.of(AgentTool.readOnlyOf(
                "quests_active",
                "Read the caller's active PikaMug Quests, current stages and native objective counters. "
                        + "Only existing player records are read. Counters do not include all custom conditions; "
                        + "do not infer completion from counters alone.",
                object(),
                this::allowed,
                null,
                (context, args) -> active(context.player())));
    }

    private boolean allowed(Player player) {
        return installed != null
                && api != null
                && installed.isEnabled()
                && player.hasPermission("pandagpt.use")
                && player.hasPermission("quests.quest");
    }

    private ToolResult active(Player player) {
        if (!allowed(player)) {
            return ToolResult.error("Quests is disabled or the player lacks quests.quest permission.");
        }
        try {
            if ((boolean) api.loading.invoke(installed)) {
                return ToolResult.error("Quests is still loading. Retry later.");
            }
            Object quester = null;
            int scanned = 0;
            // getQuester(UUID) creates missing records; getOnlineQuesters() scans the entire offline cache.
            for (Object candidate : (Collection<?>) api.offlineQuesters.invoke(installed)) {
                if (scanned++ == MAX_QUESTERS) {
                    return ToolResult.error("Quests player lookup reached its bounded scan limit; retry later.");
                }
                if (player.getUniqueId().equals(api.uuid.invoke(candidate))) {
                    quester = candidate;
                    break;
                }
            }
            if (quester == null) {
                return ToolResult.error("Quests has no loaded record for this player yet.");
            }
            Map<?, ?> active = (Map<?, ?>) api.currentQuests.invoke(quester);
            Map<?, ?> progress = (Map<?, ?>) api.progress.invoke(quester);
            StringBuilder out = new StringBuilder("Active quests: " + active.size()
                    + "\nNative objective counters; additional conditions, locations and custom display rules may apply. "
                    + "Use /quest for the server's full objective descriptions.\n");
            int shown = 0;
            for (Map.Entry<?, ?> entry : active.entrySet()) {
                if (shown++ == MAX_QUESTS || out.length() >= MAX_OUTPUT - 2500) {
                    out.append("[More active quests omitted by output limit.]\n");
                    break;
                }
                Object quest = entry.getKey();
                out.append("quest: ")
                        .append(clean(api.questName.invoke(quest), 160))
                        .append(" [")
                        .append(clean(api.questId.invoke(quest), 100))
                        .append("]")
                        .append(", stage: ")
                        .append(((Number) entry.getValue()).intValue() + 1)
                        .append('\n');
                Object data = progress.get(quest);
                Object stage = api.currentStage.invoke(quester, quest);
                if (data == null || stage == null) {
                    out.append("  Progress has not loaded; no default progress was created.\n");
                    continue;
                }
                appendObjectives(out, stage, data);
            }
            return ToolResult.ok(out.toString());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return ToolResult.error("Quests API is unavailable or incompatible; no quest data was changed.");
        }
    }

    private void appendObjectives(StringBuilder out, Object stage, Object progress)
            throws ReflectiveOperationException {
        int count = 0;
        for (Counter counter : api.counters) {
            List<?> targets = (List<?>) counter.targets.invoke(stage);
            List<?> counts = (List<?>) counter.progress.invoke(progress);
            List<?> goals = counter.goals == null ? null : (List<?>) counter.goals.invoke(stage);
            for (int i = 0; i < targets.size(); i++) {
                if (count++ >= MAX_OBJECTIVES || out.length() >= MAX_OUTPUT - 350) {
                    out.append("  [More objectives omitted by output limit.]\n");
                    return;
                }
                Object target = targets.get(i);
                String name;
                int goal;
                if (target instanceof ItemStack stack) {
                    name = stack.getType().name();
                    goal = stack.getAmount();
                } else if (api.blockStackType.isInstance(target)) {
                    name = ((Material) api.blockType.invoke(target)).name();
                    goal = ((Number) api.blockAmount.invoke(target)).intValue();
                } else {
                    name = api.customObjectiveType.isInstance(target)
                            ? clean(api.customName.invoke(target), 100)
                            : target instanceof Enum<?> value ? value.name() : clean(target, 100);
                    goal = goals == null
                            ? 1
                            : i < goals.size() && goals.get(i) instanceof Number value ? value.intValue() : -1;
                }
                // A missing counter is unknown, not zero: avoid inventing progress while data is loading.
                String current = i >= counts.size()
                        ? "unknown"
                        : counts.get(i) instanceof Boolean value ? (value ? "1" : "0") : clean(counts.get(i), 25);
                out.append("  ")
                        .append(counter.label)
                        .append(' ')
                        .append(name)
                        .append(": ")
                        .append(current)
                        .append('/')
                        .append(goal < 0 ? "unknown" : goal)
                        .append('\n');
            }
        }
        for (Scalar scalar : api.scalars) {
            Object goal = scalar.goal.invoke(stage);
            if (goal instanceof Number number && number.intValue() > 0) {
                if (count++ >= MAX_OBJECTIVES || out.length() >= MAX_OUTPUT - 350) {
                    out.append("  [More objectives omitted by output limit.]\n");
                    return;
                }
                out.append("  ")
                        .append(scalar.label)
                        .append(": ")
                        .append(scalar.progress.invoke(progress))
                        .append('/')
                        .append(number.intValue())
                        .append('\n');
            }
        }
    }

    private static String clean(Object value, int max) {
        String text = ChatColor.stripColor(String.valueOf(value)).replaceAll("[\\p{Cntrl}]", " ");
        return text.length() > max ? text.substring(0, max) + "…" : text;
    }

    record Counter(String label, Method targets, Method progress, Method goals) {}

    record Scalar(String label, Method goal, Method progress) {}

    /** Exact public API methods, resolved once at activation; no arbitrary reflective method calls. */
    static final class Api {
        final Class<?> questsType;
        final Class<?> blockStackType;
        final Class<?> customObjectiveType;
        final Method loading, offlineQuesters, uuid, currentQuests, progress, currentStage, questName, questId;
        final Method blockType, blockAmount, customName;
        final List<Counter> counters;
        final List<Scalar> scalars;

        Api(
                Class<?> quests,
                Class<?> quester,
                Class<?> bukkitQuester,
                Class<?> quest,
                Class<?> stage,
                Class<?> data,
                Class<?> blockStack,
                Class<?> customObjective)
                throws ReflectiveOperationException {
            questsType = quests;
            blockStackType = blockStack;
            customObjectiveType = customObjective;
            loading = method(quests, "isLoading", boolean.class);
            offlineQuesters = method(quests, "getOfflineQuesters", Collection.class);
            uuid = method(quester, "getUUID", UUID.class);
            currentQuests = method(quester, "getCurrentQuests", Map.class);
            progress = method(bukkitQuester, "getQuestProgress", Map.class);
            currentStage = method(quester, "getCurrentStage", stage, quest);
            questName = method(quest, "getName", String.class);
            questId = method(quest, "getId", String.class);
            blockType = method(blockStack, "getType", Material.class);
            blockAmount = method(blockStack, "getAmount", int.class);
            customName = method(customObjective, "getName", String.class);
            Map<String, String[]> groups = new LinkedHashMap<>();
            groups.put("break", new String[] {"BlocksToBreak", "BlocksBroken"});
            groups.put("damage", new String[] {"BlocksToDamage", "BlocksDamaged"});
            groups.put("place", new String[] {"BlocksToPlace", "BlocksPlaced"});
            groups.put("use", new String[] {"BlocksToUse", "BlocksUsed"});
            groups.put("cut", new String[] {"BlocksToCut", "BlocksCut"});
            groups.put("craft", new String[] {"ItemsToCraft", "ItemsCrafted"});
            groups.put("smelt", new String[] {"ItemsToSmelt", "ItemsSmelted"});
            groups.put("enchant", new String[] {"ItemsToEnchant", "ItemsEnchanted"});
            groups.put("brew", new String[] {"ItemsToBrew", "ItemsBrewed"});
            groups.put("consume", new String[] {"ItemsToConsume", "ItemsConsumed"});
            groups.put("deliver", new String[] {"ItemsToDeliver", "ItemsDelivered"});
            groups.put("interact with NPC", new String[] {"NpcsToInteract", "NpcsInteracted"});
            groups.put("kill NPC", new String[] {"NpcsToKill", "NpcsNumKilled", "NpcNumToKill"});
            groups.put("kill mob", new String[] {"MobsToKill", "MobNumKilled", "MobNumToKill"});
            groups.put("tame", new String[] {"MobsToTame", "MobsTamed", "MobNumToTame"});
            groups.put("reach", new String[] {"LocationNames", "LocationsReached"});
            groups.put("shear", new String[] {"SheepToShear", "SheepSheared", "SheepNumToShear"});
            groups.put("password objective", new String[] {"PasswordDisplays", "PasswordsSaid"});
            groups.put(
                    "custom objective",
                    new String[] {"CustomObjectives", "CustomObjectiveCounts", "CustomObjectiveCounts"});
            var resolved = new java.util.ArrayList<Counter>();
            for (var group : groups.entrySet()) {
                String[] getters = group.getValue();
                resolved.add(new Counter(
                        group.getKey(),
                        method(stage, "get" + getters[0], List.class),
                        method(data, "get" + getters[1], List.class),
                        getters.length == 3 ? method(stage, "get" + getters[2], List.class) : null));
            }
            counters = List.copyOf(resolved);
            scalars = List.of(
                    new Scalar(
                            "catch fish",
                            method(stage, "getFishToCatch", Integer.class),
                            method(data, "getFishCaught", int.class)),
                    new Scalar(
                            "milk cows",
                            method(stage, "getCowsToMilk", Integer.class),
                            method(data, "getCowsMilked", int.class)),
                    new Scalar(
                            "kill players",
                            method(stage, "getPlayersToKill", Integer.class),
                            method(data, "getPlayersKilled", int.class)));
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
