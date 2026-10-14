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
                                + "Text must be the COMPLETE CURRENT player message (max 4000 chars), including negation and conditions. "
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
                                string(
                                        "text",
                                        "Complete current player message, verbatim, max 4000 chars; never extract a substring",
                                        true),
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
                            String text = args.has("text") && args.get("text").isJsonPrimitive()
                                    ? args.get("text").getAsString()
                                    : "";
                            if (context.turn().kind() != AgentTurn.Kind.CONVERSATION
                                    || text.isBlank()
                                    || !context.turn().playerEvidence().equals(text)) {
                                return CompletableFuture.completedFuture(
                                        ToolResult.error(
                                                "Memory must preserve the COMPLETE current player message, including negation and conditions."));
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
                AgentTool.readOnlyAsync(
                        "recall_game_memory",
                        "Find the correct past game event/fact/plan/lesson when the player needs it. Also searches existing places, notes and goal. "
                                + "Use specific keywords in the player's language. Returns original evidence, source, time, world and status, plus evidence-linked semantic interpretations and relationships when ready. "
                                + "Historical, expired, resolved or needs_verification results are not proof of current world state. No match means unknown.",
                        object(
                                string(
                                        "query",
                                        "Specific keywords or exact evidence UUID (UUID returns one complete record without relation expansion); max 200 chars",
                                        true),
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
                                            ? ToolResult.ok(recallResult(plugin, recall))
                                            : ToolResult.error("Game memory unavailable; do not infer a past event."));
                        }),
                AgentTool.async(
                        "distill_memory",
                        "Run a separate semantic model pass over this player's original evidence, producing grounded meanings and relationships "
                                + "between game experiences, plans and lessons. Summaries/links are interpretations with evidence IDs; raw evidence is preserved. "
                                + "Call on memory organization requests or when new experiences need relating. May fail if evidence changed; retry then.",
                        object(),
                        p -> available(plugin) && plugin.memory().settings().distillation(),
                        ToolSupport.activity("memory"),
                        (context, args) -> plugin.memory()
                                .distillGame(context.player().getUniqueId())
                                .handle((state, error) -> error == null
                                        ? ToolResult.ok("Memory distillation persisted: "
                                                + state.events().size() + " evidence records, "
                                                + state.meanings().size() + " semantic interpretations, "
                                                + state.relations().size() + " experience relationships.")
                                        : ToolResult.error(
                                                "Memory distillation failed; previous evidence preserved."))),
                AgentTool.async(
                        "forget_game_memory",
                        "Preview deletion using an evidence ID. Without token this does NOT delete: return the affected evidence and exact confirmation phrase to the player. "
                                + "Delete only in a NEW conversation turn whose complete player message equals that phrase, using the issued token and same ID. "
                                + "Token is one-use, bound to player and memory revision, expires in five minutes. For crafting goals use clear_goal.",
                        object(
                                string("id", "Exact evidence UUID from recall_game_memory", true),
                                string("token", "Preview token; omit to preview deletion", false)),
                        p -> available(plugin),
                        null,
                        (context, args) -> {
                            UUID id;
                            try {
                                id = UUID.fromString(Schema.text(args, "id"));
                            } catch (IllegalArgumentException e) {
                                return CompletableFuture.completedFuture(ToolResult.error("Invalid evidence ID."));
                            }
                            if (context.turn().kind() != AgentTurn.Kind.CONVERSATION)
                                return CompletableFuture.completedFuture(
                                        ToolResult.error("Deletion requires a player conversation."));
                            String tokenText = Schema.text(args, "token");
                            if (tokenText.isBlank()) {
                                return plugin.memory()
                                        .prepareForget(context.player().getUniqueId(), id)
                                        .handle(
                                                (preview, error) -> error == null
                                                        ? ToolResult.ok(
                                                                "Deletion PREVIEW ONLY; nothing deleted. Show all affected records and ask the player to send the confirmation phrase: "
                                                                        + JSON.toJson(preview))
                                                        : ToolResult.error(
                                                                "Cannot preview this deletion; use clear_goal for crafting goals."));
                            }
                            UUID token;
                            try {
                                token = UUID.fromString(tokenText);
                            } catch (IllegalArgumentException e) {
                                return CompletableFuture.completedFuture(
                                        ToolResult.error("Invalid confirmation token."));
                            }
                            return plugin.memory()
                                    .confirmForget(
                                            context.player().getUniqueId(),
                                            id,
                                            token,
                                            context.turn().playerEvidence())
                                    .handle((removed, error) -> error != null
                                            ? ToolResult.error(
                                                    "Deletion not authorized or failed: confirmation missing/expired or memory changed; preview again.")
                                            : removed
                                                    ? ToolResult.ok("Memory and its superseded versions forgotten.")
                                                    : ToolResult.error("Memory ID not found."));
                        }));
    }

    private static String recallResult(ChatGPTIntegration plugin, GameMemory.Recall recall) {
        int budget =
                plugin.settings() == null ? 6000 : plugin.settings().agent().maxToolResultChars();
        String complete = JSON.toJson(recall);
        if (complete.length() <= budget) return complete;
        var root = new com.google.gson.JsonObject();
        root.addProperty("outcome", "evidence_requires_id_lookup");
        root.addProperty(
                "notice",
                "References only, no partial quotes. Query an exact evidence UUID for one complete record. If that record exceeds budget, increase max_tool_result_chars.");
        var references = new com.google.gson.JsonArray();
        root.add("references", references);
        int omitted = recall.matches().size();
        root.addProperty("omitted_references", omitted);
        for (var hit : recall.matches()) {
            var ref = new com.google.gson.JsonObject();
            ref.addProperty("evidence_id", hit.evidence().id().toString());
            ref.addProperty("topic", hit.evidence().topic());
            ref.addProperty("status", hit.status());
            references.add(ref);
            root.addProperty("omitted_references", --omitted);
            if (JSON.toJson(root).length() > budget) {
                references.remove(references.size() - 1);
                root.addProperty("omitted_references", ++omitted);
                break;
            }
        }
        return JSON.toJson(root);
    }

    static CompletableFuture<ToolResult> forgetLegacy(
            ChatGPTIntegration plugin,
            cn.panda.pandagpt.agent.ToolContext context,
            com.google.gson.JsonObject args,
            boolean place) {
        if (context.turn().kind() != AgentTurn.Kind.CONVERSATION)
            return CompletableFuture.completedFuture(ToolResult.error("Deletion requires a player conversation."));
        UUID token = null;
        String value = Schema.text(args, "token");
        if (!value.isBlank()) {
            try {
                token = UUID.fromString(value);
            } catch (IllegalArgumentException e) {
                return CompletableFuture.completedFuture(ToolResult.error("Invalid confirmation token."));
            }
        }
        return plugin.memory()
                .forgetLegacy(
                        context.player().getUniqueId(),
                        context.player().getWorld().getUID(),
                        Schema.text(args, place ? "name" : "contains"),
                        place,
                        token,
                        context.turn().playerEvidence())
                .handle(
                        (result, error) -> error == null
                                ? ToolResult.ok(result)
                                : ToolResult.error(
                                        "Deletion not authorized or failed; preview again and obtain the exact player confirmation."));
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
