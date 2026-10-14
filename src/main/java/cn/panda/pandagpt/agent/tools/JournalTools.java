package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.features.DeathLog;
import cn.panda.pandagpt.perception.SpatialMemory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.advancement.Advancement;

/** “journal” 技能的工具：死亡回溯、统计、成就进度与个人笔记。 */
public final class JournalTools {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.readOnlyOf(
                        "last_death",
                        "Where, when and how the player last died, what they dropped and whether the drops may still "
                                + "be on the ground (items despawn after 5 minutes).",
                        object(),
                        p -> plugin.features() != null,
                        null,
                        (context, args) -> {
                            DeathLog.Death death = plugin.features()
                                    .deaths()
                                    .last(context.player().getUniqueId());
                            if (death == null) {
                                return ToolResult.ok("No death recorded since the server started.");
                            }
                            long age = plugin.features().clock().millis() - death.time();
                            return ToolResult.ok("Died in " + death.worldName() + " at " + death.position() + " "
                                    + Duration.ofMillis(age).toMinutes() + " minutes ago. Cause: " + death.cause()
                                    + ". Levels before death: " + death.levels() + ". Drops: "
                                    + (death.drops().isEmpty() ? "none (keepInventory or empty)" : death.drops())
                                    + (age < DeathLog.DESPAWN_MILLIS
                                            ? ". Drops may still be there for about "
                                                    + (DeathLog.DESPAWN_MILLIS - age) / 1000 + " seconds"
                                                    + " (only while the chunk stays loaded counts down)."
                                            : ". Drops have most likely despawned.")
                                    + " Use guide_to with target 'death' to lead the player there.");
                        }),
                AgentTool.readOnlyOf(
                        "player_statistics",
                        "Lifetime statistics and the change since the player joined this session: play time, deaths, "
                                + "kills, distances, fishing, breeding, trading and ores mined.",
                        object(),
                        p -> plugin.features() != null,
                        ToolSupport.activity("status"),
                        (context, args) -> {
                            var report = plugin.features().stats().report(context.player());
                            StringBuilder out = new StringBuilder("statistic: lifetime (this session)\n");
                            report.forEach((key, values) -> out.append(key)
                                    .append(": ")
                                    .append(values[0])
                                    .append(" (+")
                                    .append(values[1])
                                    .append(")\n"));
                            out.append("PLAY_ONE_MINUTE is in ticks (20 per second); *_ONE_CM values are centimetres.");
                            return ToolResult.ok(out.toString());
                        }),
                AgentTool.readOnlyOf(
                        "advancement_progress",
                        "Advancements the player has not finished yet, closest to completion first, with remaining "
                                + "criteria. Use it to suggest what to do next.",
                        object(string("filter", "Optional keyword to match titles or IDs", false)),
                        p -> true,
                        null,
                        (context, args) -> ToolResult.ok(advancements(context.player(), Schema.text(args, "filter")))),
                AgentTool.async(
                        "add_note",
                        "Save a personal note for the player: a to-do, a fact about them (preferences, play style) or "
                                + "a general note. Notes persist and appear in your context in later conversations. "
                                + "Only on request or clear lasting preference. Text must be the COMPLETE current player message, verbatim (max 200 chars); for longer messages use remember_game_memory.",
                        object(
                                string(
                                        "text",
                                        "Complete current player message, verbatim, at most 200 characters",
                                        true),
                                choice("kind", "Note kind", false, "todo", "fact", "note")),
                        p -> plugin.memory() != null,
                        ToolSupport.activity("memory"),
                        (context, args) -> {
                            String kind = Schema.text(args, "kind").toLowerCase(Locale.ROOT);
                            if (!List.of("todo", "fact", "note").contains(kind)) {
                                kind = "note";
                            }
                            String text = args.has("text") && args.get("text").isJsonPrimitive()
                                    ? args.get("text").getAsString()
                                    : "";
                            if (context.turn().kind() != cn.panda.pandagpt.agent.AgentTurn.Kind.CONVERSATION
                                    || text.isBlank()
                                    || !text.equals(context.turn().playerEvidence()))
                                return java.util.concurrent.CompletableFuture.completedFuture(
                                        ToolResult.error(
                                                "Notes must preserve the complete current player message; no substring or assistant interpretation."));
                            return plugin.memory()
                                    .persistNote(
                                            context.player().getUniqueId(),
                                            new SpatialMemory.Note(
                                                    text,
                                                    kind,
                                                    plugin.features() == null
                                                            ? System.currentTimeMillis()
                                                            : plugin.features()
                                                                    .clock()
                                                                    .millis()))
                                    .handle(
                                            (saved, error) -> error == null
                                                    ? ToolResult.ok("Note persisted.")
                                                    : ToolResult.error(
                                                            "Note could not be persisted; for messages over 200 chars use remember_game_memory."));
                        }),
                AgentTool.readOnlyOf(
                        "list_notes",
                        "All of the player's saved notes with their numbers.",
                        object(),
                        p -> plugin.memory() != null,
                        ToolSupport.activity("memory"),
                        (context, args) -> {
                            var notes = plugin.memory()
                                    .view(context.player().getUniqueId())
                                    .notes();
                            if (notes.isEmpty()) {
                                return ToolResult.ok("No notes.");
                            }
                            List<String> lines = new ArrayList<>();
                            for (int i = 0; i < notes.size(); i++) {
                                lines.add((i + 1) + ". [" + notes.get(i).kind() + "] "
                                        + notes.get(i).text());
                            }
                            return ToolResult.ok(String.join("\n", lines));
                        }),
                AgentTool.async(
                        "remove_note",
                        "Preview deleting notes containing this text; show ALL affected records and the exact confirmation phrase. "
                                + "Use token only after the player sends that phrase as the complete current message. Never deletes without confirmation.",
                        object(
                                string("contains", "Text to match", true),
                                string("token", "One-use preview token; omit to preview", false)),
                        p -> plugin.memory() != null
                                && plugin.memory().settings().enabled(),
                        null,
                        (context, args) -> MemoryTools.forgetLegacy(plugin, context, args, false)));
    }

    // 未完成的成就按完成比例排序；跳过配方类与没有显示信息的隐藏成就。
    static String advancements(org.bukkit.entity.Player player, String filter) {
        record Entry(String id, String title, String description, int done, int total, List<String> remaining) {}
        List<Entry> entries = new ArrayList<>();
        String needle = filter.toLowerCase(Locale.ROOT);
        var iterator = player.getServer().advancementIterator();
        while (iterator.hasNext()) {
            Advancement advancement = iterator.next();
            String id = advancement.getKey().toString();
            var display = advancement.getDisplay();
            if (id.contains(":recipes/") || display == null) {
                continue;
            }
            var progress = player.getAdvancementProgress(advancement);
            if (progress.isDone()) {
                continue;
            }
            String title = PLAIN.serialize(display.title());
            if (!needle.isEmpty() && !title.toLowerCase(Locale.ROOT).contains(needle) && !id.contains(needle)) {
                continue;
            }
            Advancement parent = advancement.getParent();
            if (parent != null && !player.getAdvancementProgress(parent).isDone()) {
                continue;
            }
            entries.add(new Entry(
                    id,
                    title,
                    PLAIN.serialize(display.description()),
                    progress.getAwardedCriteria().size(),
                    advancement.getCriteria().size(),
                    progress.getRemainingCriteria().stream().limit(8).toList()));
        }
        entries.sort(Comparator.comparingDouble((Entry e) -> -(double) e.done() / Math.max(1, e.total())));
        if (entries.isEmpty()) {
            return "No unfinished advancement is currently reachable" + (needle.isEmpty() ? "." : " for that filter.");
        }
        return entries.stream()
                .limit(12)
                .map(e -> e.title() + " (" + e.id() + ") " + e.done() + "/" + e.total() + ": " + e.description()
                        + (e.total() > 1 ? "; remaining: " + e.remaining() : ""))
                .collect(Collectors.joining("\n"));
    }

    private JournalTools() {}
}
