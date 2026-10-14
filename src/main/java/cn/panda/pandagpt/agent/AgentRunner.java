package cn.panda.pandagpt.agent;

import cn.panda.pandagpt.ai.AiProvider;
import cn.panda.pandagpt.ai.AiRequest;
import cn.panda.pandagpt.ai.AiResponse;
import cn.panda.pandagpt.ai.Message;
import cn.panda.pandagpt.ai.ToolCall;
import cn.panda.pandagpt.config.AgentSettings;
import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Localized;
import cn.panda.pandagpt.util.FormatUtil;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Agent 的多步推理循环：请求模型 → 执行工具 → 把结果交回模型 → 重复，直到模型给出最终回答或预算耗尽。
 *
 * <p>线程：{@link #run} 在 AI 工作线程执行并阻塞等待；每个工具都通过 {@link MainThread} 切回服务器主线程执行，
 * 工作线程从不直接访问 Bukkit 对象。
 */
public final class AgentRunner {
    /** 把任务切到服务器主线程执行。 */
    @FunctionalInterface
    public interface MainThread {
        <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> task);
    }

    /**
     * 一个回合的输入。
     *
     * @param tools 本回合可用的工具（已在主线程按权限筛选）
     * @param cancelled 会话被清除、玩家离线或插件重载时返回 true
     */
    public record Plan(
            AgentTurn turn,
            String language,
            String systemPrompt,
            List<Message> messages,
            Map<String, AgentTool> tools,
            BooleanSupplier cancelled) {}

    /**
     * 回合结果。
     *
     * @param exhausted 达到步数或工具调用上限后被要求直接回答
     */
    public record Outcome(String text, int steps, boolean exhausted) {}

    /** 读取技能说明的元工具名。 */
    public static final String LOAD_SKILL = "load_skill";

    private final AiProvider provider;
    private final AgentSettings settings;
    private final SkillLibrary skills;
    private final MainThread main;
    private final Function<UUID, Player> players;

    public AgentRunner(
            AiProvider provider,
            AgentSettings settings,
            SkillLibrary skills,
            MainThread main,
            Function<UUID, Player> players) {
        this.provider = provider;
        this.settings = settings;
        this.skills = skills;
        this.main = main;
        this.players = players;
    }

    public Outcome run(Plan plan) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(settings.turnTimeoutSeconds());
        List<Message> messages = new ArrayList<>(plan.messages());
        List<JsonObject> declarations = new ArrayList<>();
        declarations.add(loadSkillDeclaration());
        plan.tools().values().forEach(tool -> declarations.add(tool.declaration()));
        int calls = 0;
        for (int step = 1; ; step++) {
            if (plan.cancelled().getAsBoolean()) {
                throw new CancellationException();
            }
            // 最后一步或预算耗尽时仍发送工具声明（Gemini 要求历史中的函数调用有对应声明），但禁止再调用。
            boolean last =
                    step >= settings.maxSteps() || calls >= settings.maxToolCalls() || System.nanoTime() > deadline;
            AiResponse response = provider.generate(new AiRequest(plan.systemPrompt(), messages, declarations, last));
            if (response.toolCalls().isEmpty() || last) {
                return new Outcome(
                        response.text(), step, last && !response.toolCalls().isEmpty());
            }
            messages.add(Message.assistant(response.text(), response.toolCalls(), response.signature()));
            AiRequest.Image image = null;
            List<ToolCall> batch = response.toolCalls();
            for (int at = 0; at < batch.size(); ) {
                int end = at + 1;
                AgentTool first = plan.tools().get(batch.get(at).name());
                if (first != null && first.independentRead()) {
                    while (end < batch.size() && end - at < settings.maxParallelReads()) {
                        AgentTool next = plan.tools().get(batch.get(end).name());
                        if (next == null || !next.independentRead()) break;
                        end++;
                    }
                }
                List<CompletableFuture<ToolResult>> pending = new ArrayList<>();
                try {
                    for (int i = at; i < end; i++) {
                        pending.add(
                                ++calls > settings.maxToolCalls()
                                        ? CompletableFuture.completedFuture(
                                                ToolResult.error("Tool budget exhausted; answer now."))
                                        : start(plan, batch.get(i), deadline));
                    }
                    for (int i = at; i < end; i++) {
                        ToolResult result = await(plan, pending.get(i - at), deadline);
                        messages.add(Message.tool(batch.get(i), boundedResult(batch.get(i), result)));
                        if (result.image() != null) image = result.image();
                    }
                } finally {
                    pending.forEach(f -> {
                        if (!f.isDone()) f.cancel(true);
                    });
                }
                at = end;
            }
            // 工具消息只能承载文字，图片以紧随其后的用户消息附上。
            if (image != null) {
                messages.add(Message.user(
                        "[Image returned by the look tool above. Use it together with its text "
                                + "result; it is a server-side reconstruction, not a client screenshot.]",
                        image));
            }
        }
    }

    private String boundedResult(ToolCall call, ToolResult result) {
        int max = settings.maxToolResultChars();
        if (result.content().length() <= max) return result.content();
        // Evidence and deletion previews must never silently lose negation, scope or affected records.
        if (call.name().contains("memory")
                || call.name().equals("forget_place")
                || call.name().equals("remove_note")
                || call.name().equals("list_notes"))
            return FormatUtil.truncate(
                    "ERROR: Complete memory evidence/preview exceeds tool result budget. Narrow the query or increase max_tool_result_chars; no partial evidence is supplied.",
                    max);
        String marker = " [TRUNCATED: missing context is unknown]";
        return FormatUtil.truncate(result.content(), Math.max(0, max - marker.length())) + marker;
    }

    private CompletableFuture<ToolResult> start(Plan plan, ToolCall call, long deadline) {
        JsonObject args;
        try {
            args = call.arguments().isBlank()
                    ? new JsonObject()
                    : JsonParser.parseString(call.arguments()).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException e) {
            return CompletableFuture.completedFuture(ToolResult.error("Arguments must be a JSON object."));
        }
        if (call.name().equals(LOAD_SKILL)) {
            Skill skill = skills.get(Schema.text(args, "name"));
            return CompletableFuture.completedFuture(
                    skill == null
                            ? ToolResult.error("Unknown skill. Use a name from the loadable skills list.")
                            : ToolResult.ok(skill.instructions()));
        }
        AgentTool tool = plan.tools().get(call.name());
        if (tool == null) {
            return CompletableFuture.completedFuture(
                    ToolResult.error("Tool " + call.name() + " is not available to this player right now."));
        }
        try {
            return main.submit(() -> {
                Player player = players.apply(plan.turn().player());
                if (player == null || !player.isOnline() || plan.cancelled().getAsBoolean()) {
                    return CompletableFuture.failedFuture(new CancellationException());
                }
                if (System.nanoTime() >= deadline) {
                    return CompletableFuture.completedFuture(ToolResult.error("Tool timed out before execution."));
                }
                if (!tool.available(player)) {
                    return CompletableFuture.completedFuture(ToolResult.error("Tool is no longer available."));
                }
                plan.turn().toolsUsed().add(tool.name());
                if (tool.activity() != null && plan.turn().kind() == AgentTurn.Kind.CONVERSATION) {
                    player.sendActionBar(
                            Lang.component(player, Lang.Prefix.NONE, NamedTextColor.GRAY, tool.activity()));
                }
                return tool.call(new ToolContext(player, plan.turn(), plan.language()), args);
            });
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private ToolResult await(Plan plan, CompletableFuture<ToolResult> future, long deadline)
            throws InterruptedException {
        try {
            while (true) {
                if (plan.cancelled().getAsBoolean()) throw new CancellationException();
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    future.cancel(true);
                    return ToolResult.error("Tool timed out.");
                }
                try {
                    return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    // Recheck turn cancellation and the shared deadline while reads are pending.
                }
            }
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CancellationException cancelled) {
                throw cancelled;
            }
            // 本地化异常的信息已脱敏，可以直接告诉模型原因。
            return ToolResult.error(
                    cause instanceof Localized localized
                            ? localized.localized().render("en_US")
                            : "Tool failed: " + cause.getClass().getSimpleName());
        }
    }

    /** load_skill 元工具的声明。 */
    public static JsonObject loadSkillDeclaration() {
        JsonObject function = new JsonObject();
        function.addProperty("name", LOAD_SKILL);
        function.addProperty(
                "description",
                "Read the full instructions of a loadable skill before using its tools for something non-trivial.");
        function.add("parameters", Schema.object(Schema.string("name", "Skill name from the catalogue", true)));
        return function;
    }
}
