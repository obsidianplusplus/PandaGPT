package cn.panda.pandagpt.agent.tools;

import static cn.panda.pandagpt.agent.Schema.bool;
import static cn.panda.pandagpt.agent.Schema.choice;
import static cn.panda.pandagpt.agent.Schema.object;
import static cn.panda.pandagpt.agent.Schema.string;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.Schema;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.memory.GameMemory;
import com.google.gson.Gson;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** 游戏记忆工具：原始证据写入、需求检索、独立蒸馏与遗忘；写入结果等待实际落盘。 */
public final class MemoryTools {
    private static final Gson JSON = new Gson();

    public static List<AgentTool> create(ChatGPTIntegration plugin) {
        return List.of(
                AgentTool.async(
                        "remember_game_memory",
                        "Save a game experience, lasting fact/preference, plan or lesson explicitly stated by this player. "
                                + "Text must be an exact quote from the CURRENT player message (max 400 chars), never your answer. "
                                + "Use the same topic to update a fact/plan/lesson; resolved closes a plan. Only on request or clear lasting preference.",
                        object(
                                choice(
                                        "kind",
                                        "Independent memory pipeline",
                                        true,
                                        "episode",
                                        "fact",
                                        "plan",
                                        "lesson"),
                                string("topic", "Stable short key for this particular thing, max 64 chars", true),
                                string("text", "Exact quote from this player's current message, max 400 chars", true),
                                string(
                                        "cues",
                                        "Optional retrieval keywords separated by commas; at most 8, each max 64 chars",
                                        false),
                                choice(
                                        "scope",
                                        "Current world, or global for personal preferences/cross-world plans",
                                        false,
                                        "world",
                                        "global"),
                                choice(
                                        "status",
                                        "Use resolved only when the player explicitly finished/cancelled this plan",
                                        false,
                                        "active",
                                        "resolved")),
                        p -> available(plugin),
                        ToolSupport.activity("memory"),
                        (context, args) -> {
                            String text = Schema.text(args, "text");
                            if (context.turn().kind() != AgentTurn.Kind.CONVERSATION
                                    || text.isBlank()
                                    || !context.turn().playerEvidence().contains(text)) {
                                return CompletableFuture.completedFuture(
                                        ToolResult.error("Memory must quote the current player's message exactly."));
                            }
                            GameMemory.Kind kind = kind(Schema.text(args, "kind"));
                            if (kind == null
                                    || (Schema.text(args, "status").equals("resolved")
                                            && kind != GameMemory.Kind.PLAN)) {
                                return CompletableFuture.completedFuture(
                                        ToolResult.error("Invalid memory kind/status; only plans can be resolved."));
                            }
                            List<String> cues = Arrays.stream(
                                            Schema.text(args, "cues").split("[,，]"))
                                    .map(String::strip)
                                    .filter(s -> !s.isEmpty())
                                    .distinct()
                                    .toList();
                            var event = new GameMemory.Event(
                                    UUID.randomUUID(),
                                    kind,
                                    Schema.text(args, "topic"),
                                    text,
                                    cues,
                                    GameMemory.Source.PLAYER,
                                    Schema.text(args, "scope").equals("global")
                                            ? null
                                            : context.player().getWorld().getUID(),
                                    null,
                                    now(plugin),
                                    0,
                                    Schema.text(args, "status").equals("resolved")
                                            ? GameMemory.Status.RESOLVED
                                            : GameMemory.Status.ACTIVE);
                            if (!GameMemory.valid(event)) {
                                return CompletableFuture.completedFuture(
                                        ToolResult.error("Memory topic, text or cues exceed their limits."));
                            }
                            return plugin.memory()
                                    .rememberGame(context.player().getUniqueId(), event)
                                    .handle((id, error) -> error == null
                                            ? ToolResult.ok("Memory persisted. Evidence ID: " + id)
                                            : ToolResult.error(
                                                    "Memory could not be saved; do not claim it was remembered."));
                        }),
                AgentTool.async(
                        "recall_game_memory",
                        "Find the correct past game event/fact/plan/lesson when the player needs it. Also searches existing places, notes and goal. "
                                + "Use specific keywords in the player's language. Returns original evidence, source, time, world and status. "
                                + "Historical, expired, resolved or needs_verification results are not proof of current world state. No match means unknown.",
                        object(
                                string("query", "What the player needs to recover; max 200 chars", true),
                                choice("kind", "Optional category", false, "episode", "fact", "plan", "lesson"),
                                bool("all_worlds", "Search other worlds only when the player requests it"),
                                bool(
                                        "include_history",
                                        "Include superseded, expired or resolved evidence for past-event questions")),
                        p -> available(plugin),
                        ToolSupport.activity("memory"),
                        (context, args) -> {
                            String category = Schema.text(args, "kind");
                            if (!category.isBlank() && kind(category) == null) {
                                return CompletableFuture.completedFuture(ToolResult.error("Unknown memory category."));
                            }
                            return plugin.memory()
                                    .recallGame(
                                            context.player().getUniqueId(),
                                            context.player().getWorld().getUID(),
                                            Schema.text(args, "query"),
                                            kind(category),
                                            Schema.flag(args, "all_worlds"),
                                            Schema.flag(args, "include_history"),
                                            now(plugin))
                                    .handle((recall, error) -> error == null
                                            ? ToolResult.ok(JSON.toJson(recall))
                                            : ToolResult.error("Game memory unavailable; do not infer a past event."));
                        }),
                AgentTool.async(
                        "distill_memory",
                        "Run independent episodes, facts, plans and lessons pipelines over this player's own evidence. "
                                + "Each pipeline deduplicates within its own scope and keeps source IDs. Never invents facts or deletes raw evidence. "
                                + "Automatic after memory changes; call when the player requests memory organization.",
                        object(),
                        p -> available(plugin) && plugin.memory().settings().distillation(),
                        ToolSupport.activity("memory"),
                        (context, args) -> plugin.memory()
                                .distillGame(context.player().getUniqueId())
                                .handle((state, error) -> error == null
                                        ? ToolResult.ok("Memory distillation persisted: "
                                                + state.events().size() + " evidence records, "
                                                + state.cards().size() + " cards across episodes/facts/plans/lessons.")
                                        : ToolResult.error(
                                                "Memory distillation failed; previous evidence preserved."))),
                AgentTool.async(
                        "forget_game_memory",
                        "Forget a memory by its evidence ID from recall_game_memory, only on the player's request. "
                                + "Also removes superseded versions of that fact/plan/lesson and linked places/notes to prevent resurrection. "
                                + "For the current crafting goal use clear_goal.",
                        object(string("id", "Exact evidence UUID from recall_game_memory", true)),
                        p -> available(plugin),
                        null,
                        (context, args) -> {
                            UUID id;
                            try {
                                id = UUID.fromString(Schema.text(args, "id"));
                            } catch (IllegalArgumentException e) {
                                return CompletableFuture.completedFuture(ToolResult.error("Invalid evidence ID."));
                            }
                            return plugin.memory()
                                    .forgetGame(context.player().getUniqueId(), id)
                                    .handle((removed, error) -> error != null
                                            ? ToolResult.error(
                                                    "Could not forget this memory; use clear_goal for crafting goals.")
                                            : removed
                                                    ? ToolResult.ok("Memory and its superseded versions forgotten.")
                                                    : ToolResult.error("Memory ID not found."));
                        }));
    }

    private static boolean available(ChatGPTIntegration plugin) {
        return plugin.memory() != null && plugin.memory().settings().enabled();
    }

    private static long now(ChatGPTIntegration plugin) {
        return plugin.features() == null
                ? System.currentTimeMillis()
                : plugin.features().clock().millis();
    }

    private static GameMemory.Kind kind(String value) {
        try {
            return GameMemory.Kind.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private MemoryTools() {}
}
