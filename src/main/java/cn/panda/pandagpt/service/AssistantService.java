package cn.panda.pandagpt.service;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.actions.ActionController;
import cn.panda.pandagpt.agent.AgentRunner;
import cn.panda.pandagpt.agent.AgentRuntime;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.PromptLibrary;
import cn.panda.pandagpt.agent.SkillLibrary;
import cn.panda.pandagpt.agent.ToolRegistry;
import cn.panda.pandagpt.agent.ToolResult;
import cn.panda.pandagpt.agent.tools.RuntimeTools;
import cn.panda.pandagpt.ai.AiProvider;
import cn.panda.pandagpt.ai.Message;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Lang.Prefix;
import cn.panda.pandagpt.i18n.Localized;
import cn.panda.pandagpt.i18n.Messages;
import cn.panda.pandagpt.i18n.Msg;
import cn.panda.pandagpt.perception.PerceptionController;
import cn.panda.pandagpt.perception.QuietPolicy;
import cn.panda.pandagpt.util.FormatUtil;
import cn.panda.pandagpt.util.PandaLogger;
import cn.panda.pandagpt.util.PlayerContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Panda 的 Agent 服务：玩家在聊天中说的每一句话都成为一个 Agent 回合；插件感知到的游戏信号也可以触发主动回合。
 *
 * <p>流程：主线程采集快照、渲染提示词、按权限筛选工具 → 工作线程运行多步 Agent 循环（工具切回主线程执行）
 * → 主线程核对会话后回复并应用副作用（命令提案、清空历史）。线程：会话状态只在主线程读写。
 */
public final class AssistantService implements AutoCloseable {
    /** 一名玩家的对话会话。 */
    private static final class Session {
        final Conversation history;
        final AgentRuntime runtime;
        long lastAccepted = Long.MIN_VALUE;
        long touched;
        // 工作线程会读取它来判断回合是否已被取消。
        volatile UUID requestId;
        Future<?> task;

        Session(Settings settings, long now) {
            history = new Conversation(settings.historyRounds());
            runtime = new AgentRuntime(settings.agent().runtime());
            touched = now;
        }
    }

    /** Legacy read-only tools; independent reads and runtime controls are also eligible. */
    public static final Set<String> PROACTIVE_TOOLS = Set.of(
            "get_player_status",
            "list_places",
            "locate_place",
            "goal_status",
            "find_recipes",
            "craftable_now",
            "nearby_entities",
            "world_conditions",
            "recall_surroundings",
            "search_server_knowledge",
            "last_death",
            "advancement_progress",
            "inspect_item",
            "villager_trades",
            "list_notes",
            "recall_game_memory",
            "list_reminders",
            "economy_balance",
            "regions_here",
            "claim_here",
            "towny_plot_here",
            "lands_here",
            "essentials_homes",
            "stay_silent");

    private static final long EXPIRY_CHECK_MILLIS = 30_000;

    private final ChatGPTIntegration plugin;
    private final Settings settings;
    private final AiProvider provider;
    private final PandaLogger logger;
    private final ActionController actions;
    private final PromptLibrary prompts;
    private final SkillLibrary skills;
    private final ToolRegistry tools;
    private final AgentRunner runner;
    private final ThreadPoolExecutor workers;
    // 按插入顺序排列；提问时把会话移到末尾，因此表头始终是最久未提问的会话。
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();

    private static final class Background {
        volatile boolean cancelled;
        Future<?> task;
    }

    private final Map<UUID, Background> proactiveInFlight = new LinkedHashMap<>();
    private final ArrayDeque<UUID> followUps = new ArrayDeque<>();
    private final Set<UUID> followUpSet = new HashSet<>();
    private long ticks;
    private final Clock clock;
    // 每个回合附带的精简玩家状态；测试中可替换，避免依赖服务器注册表。
    private final Function<Player, String> snapshot;
    private volatile boolean closed;
    private long nextExpiry = Long.MIN_VALUE;

    public AssistantService(
            ChatGPTIntegration plugin,
            Settings settings,
            AiProvider provider,
            PandaLogger logger,
            PromptLibrary prompts,
            SkillLibrary skills,
            ToolRegistry tools) {
        this(
                plugin,
                settings,
                provider,
                logger,
                prompts,
                skills,
                tools,
                Clock.systemUTC(),
                ActionController.Buttons.CALLBACK,
                PlayerContext::brief);
    }

    public AssistantService(
            ChatGPTIntegration plugin,
            Settings settings,
            AiProvider provider,
            PandaLogger logger,
            PromptLibrary prompts,
            SkillLibrary skills,
            ToolRegistry tools,
            Clock clock,
            ActionController.Buttons buttons,
            Function<Player, String> snapshot) {
        this.plugin = plugin;
        this.settings = settings;
        this.provider = provider;
        this.logger = logger;
        this.prompts = prompts;
        this.skills = skills;
        this.tools = tools;
        this.clock = clock;
        this.snapshot = snapshot;
        actions = new ActionController(settings, logger, clock, buttons);
        runner = new AgentRunner(
                provider,
                settings.agent(),
                skills,
                this::onMain,
                id -> plugin.getServer().getPlayer(id),
                settings.contextBudget());
        workers = new ThreadPoolExecutor(
                settings.workers(),
                settings.workers(),
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(settings.queueSize()),
                Thread.ofPlatform().daemon().name("pandagpt-ai-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 玩家在聊天中对 Panda 说的话。 */
    public void ask(Player player, String question) {
        if (closed || !player.isOnline()) {
            return;
        }
        if (!player.hasPermission("pandagpt.use")) {
            tell(player, "service.no_permission");
            return;
        }
        if (question.isBlank()) {
            tell(player, "service.empty_question");
            return;
        }
        if (question.length() > settings.maxPromptChars()) {
            tell(player, "service.too_long");
            return;
        }
        if (settings.apiKey().isBlank()) {
            tell(player, "service.no_api_key");
            return;
        }
        if (workers.getQueue().remainingCapacity() == 0) {
            tell(player, "service.queue_full");
            return;
        }
        UUID id = player.getUniqueId();
        long now = clock.millis();
        expire();
        Session session = session(id, now);
        if (session == null) {
            tell(player, "service.sessions_full");
            return;
        }
        if (session.requestId != null) {
            tell(player, "service.in_flight");
            return;
        }
        if (session.lastAccepted != Long.MIN_VALUE && now - session.lastAccepted < settings.cooldownMillis()) {
            tell(player, "service.cooldown");
            return;
        }
        cancelProactive(id);
        String language = Messages.active().language(player);
        String system;
        Map<String, AgentTool> available;
        try {
            Map<String, String> context = variables(player, language);
            if (plugin.memory() != null && plugin.memory().settings().enabled()) {
                context.merge(
                        "memory",
                        "\n" + plugin.memory().gameContext(id, player.getWorld().getUID(), question, now),
                        String::concat);
            }
            system = render("system", context);
            available = toolsFor(player, AgentTurn.Kind.CONVERSATION);
        } catch (RuntimeException e) {
            logger.event(id, "context_error type=" + e.getClass().getSimpleName());
            tell(player, "service.context_failed");
            return;
        }
        int budget = settings.contextBudget() - system.length();
        if (question.length() > budget) {
            tell(player, "service.context_budget");
            return;
        }
        AgentTurn turn = new AgentTurn(id, AgentTurn.Kind.CONVERSATION, question);
        UUID requestId = UUID.randomUUID();
        session.requestId = requestId;
        BooleanSupplier cancelled = () -> closed || session.requestId != requestId;
        var plan = new AgentRunner.Plan(
                turn, language, system, session.history.snapshot(question, budget), available, cancelled);
        try {
            session.task = workers.submit(() -> {
                AgentRunner.Outcome outcome = null;
                Msg failure = null;
                try {
                    outcome = runner.run(plan);
                } catch (CancellationException e) {
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = Msg.of("service.cancelled");
                } catch (Exception e) {
                    // 只展示已脱敏的本地化错误；其他异常可能包含地址或密钥，统一替换为通用提示。
                    failure = e instanceof Localized localized ? localized.localized() : Msg.of("service.failed");
                }
                AgentRunner.Outcome result = outcome;
                Msg error = failure;
                schedule(() -> complete(id, session, requestId, question, turn, result, error));
            });
            session.lastAccepted = now;
            player.sendActionBar(Lang.component(player, Prefix.NONE, NamedTextColor.GRAY, Msg.of("service.thinking")));
            logger.event(id, "request_accepted provider=" + settings.provider());
        } catch (RejectedExecutionException e) {
            session.requestId = null;
            session.task = null;
            tell(player, "service.queue_full");
        }
    }

    // 取得或创建会话；会话已满时淘汰最久未提问且空闲的一个。
    private Session session(UUID id, long now) {
        Session session = sessions.remove(id);
        if (session == null) {
            if (sessions.size() >= settings.maxSessions()) {
                var iterator = sessions.entrySet().iterator();
                while (iterator.hasNext()) {
                    var entry = iterator.next();
                    if (entry.getValue().requestId == null) {
                        cancelProactive(entry.getKey());
                        iterator.remove();
                        break;
                    }
                }
                if (sessions.size() >= settings.maxSessions()) {
                    return null;
                }
            }
            session = new Session(settings, now);
        }
        session.touched = now;
        sessions.put(id, session);
        return session;
    }

    // 主线程回调：只有会话与请求编号都未变化时才回复，清除、离线或重载后的旧结果一律丢弃。
    private void complete(
            UUID id,
            Session expected,
            UUID requestId,
            String question,
            AgentTurn turn,
            AgentRunner.Outcome outcome,
            Msg failure) {
        if (closed || sessions.get(id) != expected || !requestId.equals(expected.requestId)) {
            return;
        }
        expected.requestId = null;
        expected.task = null;
        expected.touched = clock.millis();
        Player player = plugin.getServer().getPlayer(id);
        if (player == null || !player.isOnline() || !player.hasPermission("pandagpt.use")) {
            clear(id);
            return;
        }
        if (failure != null || outcome == null) {
            Lang.send(
                    player,
                    Prefix.PANDA,
                    NamedTextColor.YELLOW,
                    failure == null ? Msg.of("service.no_result") : failure);
            logger.event(id, "request_failed");
            return;
        }
        String answer = outcome.text();
        if (answer.isBlank()) {
            Lang.send(player, Prefix.PANDA, NamedTextColor.YELLOW, "agent.step_limit");
        } else {
            player.sendMessage(Component.text(Lang.text(player, "prefix.panda") + " ", NamedTextColor.AQUA)
                    .append(FormatUtil.formatChatMessage(answer)));
        }
        // 历史只保留最终回答与用过的工具名，不保留工具细节，避免上下文膨胀。
        String remembered = answer;
        if (!turn.toolsUsed().isEmpty()) {
            remembered += "\n[tools used: " + String.join(", ", turn.toolsUsed()) + "]";
        }
        if (!turn.proposals().isEmpty()) {
            remembered += "\n[Commands proposed for confirmation; none has run yet.]";
        }
        if (turn.forgetConversation()) {
            expected.history.clear();
            cancelProactive(id);
            // Forgetting chat also drops temporary runtime working state.
            expected.runtime.clear();
        } else {
            expected.history.add(question, remembered);
        }
        logger.conversation(id, question, answer);
        actions.propose(player, turn.proposals());
        logger.event(
                id,
                "request_completed steps=" + outcome.steps() + " tools="
                        + turn.toolsUsed().size());
    }

    /** A signal is evidence for model evaluation, never a preselected message. Failures stay quiet. */
    public void proactive(Player player, QuietPolicy.Candidate candidate) {
        startProactive(
                player,
                candidate.kind() + ": " + candidate.text().render("en_US"),
                candidate.expiresAt(),
                () -> plugin.perception() == null || plugin.perception().candidateValid(player, candidate));
    }

    private boolean proactiveAllowed(Player player) {
        return player.isOnline()
                && player.hasPermission("pandagpt.use")
                && !player.isDead()
                && (plugin.perception() == null || plugin.perception().proactiveAllowed(player));
    }

    private boolean occupied(Player player) {
        return busy(player.getUniqueId())
                || actions.hasPending(player.getUniqueId())
                || (plugin.perception() != null && plugin.perception().occupied(player));
    }

    private void startProactive(Player player, String signal, long expiresAt, BooleanSupplier valid) {
        UUID id = player.getUniqueId();
        if (closed
                || !settings.agent().proactive()
                || !configured()
                || !proactiveAllowed(player)
                || occupied(player)
                || proactiveInFlight.containsKey(id)
                || clock.millis() >= expiresAt
                || workers.getQueue().remainingCapacity() == 0
                || !valid.getAsBoolean()) return;
        expire();
        Session session = session(id, clock.millis());
        if (session == null) return;
        String language = Messages.active().language(player);
        String system;
        String baseline;
        Map<String, AgentTool> available;
        List<Message> messages;
        try {
            baseline = runtimeFingerprint(player);
            Map<String, String> variables = variables(player, language);
            variables.put("signal", signal);
            if (plugin.memory() != null && plugin.memory().settings().enabled()) {
                variables.merge(
                        "memory",
                        "\n"
                                + plugin.memory()
                                        .gameContext(
                                                id,
                                                player.getWorld().getUID(),
                                                session.runtime.objective() + " " + signal,
                                                clock.millis()),
                        String::concat);
            }
            system = render("proactive", variables);
            String event = "[Runtime observation, not a new player request; verify before speaking] " + signal;
            int budget = settings.contextBudget() - system.length();
            if (event.length() > budget) return;
            messages = session.history.snapshot(event, budget);
            available = toolsFor(player, AgentTurn.Kind.PROACTIVE);
        } catch (RuntimeException e) {
            logger.event(id, "proactive_skipped reason=context");
            return;
        }
        AgentTurn turn = new AgentTurn(id, AgentTurn.Kind.PROACTIVE);
        Background job = new Background();
        var plan = new AgentRunner.Plan(turn, language, system, messages, available, () -> closed || job.cancelled);
        proactiveInFlight.put(id, job);
        try {
            job.task = workers.submit(() -> {
                AgentRunner.Outcome outcome = null;
                try {
                    outcome = runner.run(plan);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    // Model failure is never converted into a routine canned message.
                }
                AgentRunner.Outcome result = outcome;
                schedule(() -> {
                    if (proactiveInFlight.get(id) != job) return;
                    proactiveInFlight.remove(id);
                    Player current = plugin.getServer().getPlayer(id);
                    if (closed
                            || job.cancelled
                            || sessions.get(id) != session
                            || current == null
                            || !proactiveAllowed(current)
                            || occupied(current)
                            || clock.millis() >= expiresAt
                            || !valid.getAsBoolean()
                            || !baseline.equals(runtimeFingerprint(current))) {
                        session.runtime.cancelFollowUp();
                        return;
                    }
                    if (result == null) {
                        session.runtime.cancelFollowUp();
                        logger.event(id, "proactive_skipped reason=model_failure");
                    } else if (turn.silent() || result.text().isBlank()) {
                        session.runtime.recordDecision("silent");
                        logger.event(id, "proactive_silent");
                    } else {
                        session.runtime.recordDecision(result.text());
                        current.sendMessage(
                                Component.text(Lang.text(current, "prefix.suggestion") + " ", NamedTextColor.GRAY)
                                        .append(FormatUtil.formatChatMessage(result.text())));
                        logger.event(id, "proactive_delivered");
                    }
                });
            });
        } catch (RejectedExecutionException e) {
            proactiveInFlight.remove(id);
            job.cancelled = true;
        }
    }

    public AgentRuntime runtime(UUID id) {
        Session session = sessions.get(id);
        return runtimeEnabled() && session != null ? session.runtime : null;
    }

    public ToolResult scheduleFollowUp(Player player, int delay, AgentRuntime.Trigger trigger, String reason) {
        AgentRuntime runtime = runtime(player.getUniqueId());
        if (runtime == null || !settings.agent().proactive() || !proactiveAllowed(player)) {
            return ToolResult.error("Proactive runtime checks are disabled or the player is in quiet mode.");
        }
        try {
            runtime.schedule(delay, trigger, reason, runtimeFingerprint(player), clock.millis());
        } catch (IllegalArgumentException e) {
            return ToolResult.error(e.getMessage());
        }
        UUID id = player.getUniqueId();
        if (followUpSet.add(id)) followUps.addLast(id);
        return ToolResult.ok(runtime.context());
    }

    /** One due session per five ticks; local checks do not use a model or survey worlds. */
    public void tick() {
        if (closed || ++ticks % 5 != 0 || followUps.isEmpty()) return;
        UUID id = followUps.removeFirst();
        followUpSet.remove(id);
        Session session = sessions.get(id);
        if (session == null || !session.runtime.waiting()) return;
        if (session.runtime.expired(clock.millis())) {
            session.runtime.cancelFollowUp();
            return;
        }
        Player player = plugin.getServer().getPlayer(id);
        if (player == null || !settings.agent().proactive() || !configured() || !proactiveAllowed(player)) {
            session.runtime.cancelFollowUp();
            return;
        }
        if (session.runtime.due(clock.millis())
                && !occupied(player)
                && !proactiveInFlight.containsKey(id)
                && workers.getQueue().remainingCapacity() > 0) {
            var wake = session.runtime.take(clock.millis(), runtimeFingerprint(player), false, false);
            if (wake != null) {
                startProactive(
                        player,
                        "FOLLOW_UP: " + wake.reason(),
                        clock.millis() + settings.agent().turnTimeoutSeconds() * 1000L,
                        () -> true);
            }
        }
        if (session.runtime.waiting() && followUpSet.add(id)) followUps.addLast(id);
    }

    public void cancelProactive(UUID id) {
        Background job = proactiveInFlight.remove(id);
        if (job != null) {
            job.cancelled = true;
            if (job.task != null) job.task.cancel(true);
        }
        Session session = sessions.get(id);
        if (session != null) session.runtime.cancelFollowUp();
        followUps.remove(id);
        followUpSet.remove(id);
        workers.purge();
    }

    private String runtimeFingerprint(Player player) {
        var facts = JsonParser.parseString(snapshot.apply(player)).getAsJsonObject();
        facts.remove("facing_yaw");
        if (facts.has("position") && facts.get("position").isJsonArray()) {
            JsonArray position = facts.getAsJsonArray("position");
            JsonArray sectors = new JsonArray();
            for (var coordinate : position) sectors.add(Math.floorDiv(coordinate.getAsInt(), 16));
            facts.add("position_sectors_16_blocks", sectors);
            facts.remove("position");
        }
        facts.addProperty("world_id", String.valueOf(player.getWorld().getUID()));
        if (plugin.perception() != null)
            facts.addProperty("activity", plugin.perception().runtimeFacts(player));
        return facts.toString();
    }

    private boolean runtimeEnabled() {
        return settings.agent().runtime().enabled() && skills.get("panda") != null;
    }

    private String render(String name, Map<String, String> variables) {
        String system = prompts.render(name, variables);
        return runtimeEnabled() ? system + "\n\n" + prompts.render("runtime", variables) : system;
    }

    // 系统提示的变量；在主线程采集，之后只作为不可变文本交给工作线程。
    private Map<String, String> variables(Player player, String language) {
        Map<String, String> variables = new LinkedHashMap<>();
        PerceptionController perception = plugin.perception();
        String personality =
                settings.personality().replace("{{player_context}}", "").replace("{{server_command_context}}", "");
        variables.put("personality", personality);
        variables.put("language", language);
        variables.put("skills", skills.catalog());
        variables.put("snapshot", snapshot.apply(player));
        AgentRuntime runtime = runtime(player.getUniqueId());
        variables.put("runtime", runtime == null ? "Runtime disabled." : runtime.context());
        variables.put("activity", perception == null ? "Activity state unavailable." : perception.runtimeFacts(player));
        variables.put("memory", perception == null ? "Perception is disabled." : perception.context(player, false));
        variables.put(
                "knowledge",
                plugin.knowledge() == null
                        ? ""
                        : String.join(", ", plugin.knowledge().outline()));
        List<String> pending = actions.pendingCommands(player.getUniqueId());
        variables.put(
                "pending",
                pending.isEmpty()
                        ? "None."
                        : "Awaiting the player's confirmation: " + pending
                                + ". If the player's message clearly agrees or declines, call respond_to_pending_action.");
        return variables;
    }

    // 已启用技能中、该玩家当前可用的工具；主动回合只开放只读工具。
    private Map<String, AgentTool> toolsFor(Player player, AgentTurn.Kind kind) {
        Set<String> enabled = new HashSet<>(skills.enabledTools());
        if (runtimeEnabled()) enabled.addAll(RuntimeTools.NAMES);
        else enabled.removeAll(RuntimeTools.NAMES);
        if (kind == AgentTurn.Kind.PROACTIVE) {
            enabled.removeIf(name -> !PROACTIVE_TOOLS.contains(name)
                    && !RuntimeTools.NAMES.contains(name)
                    && (tools.get(name) == null || !tools.get(name).independentRead()));
            enabled.add("stay_silent");
        } else {
            enabled.remove("stay_silent");
        }
        Map<String, AgentTool> available = new LinkedHashMap<>();
        for (AgentTool tool : tools.available(player, enabled)) {
            available.put(tool.name(), tool);
        }
        return available;
    }

    // 把任务切回主线程；返回的 future 在任务（及其异步结果）完成后结束。
    private <T> CompletableFuture<T> onMain(Supplier<CompletableFuture<T>> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (closed) {
            result.completeExceptionally(new CancellationException());
            return result;
        }
        schedule(() -> {
            if (result.isDone() || closed) return;
            try {
                CompletableFuture<T> child = task.get();
                result.whenComplete((value, error) -> {
                    if (result.isCancelled()) child.cancel(true);
                });
                child.whenComplete((value, error) -> {
                    if (error != null) {
                        result.completeExceptionally(
                                error instanceof CompletionException && error.getCause() != null
                                        ? error.getCause()
                                        : error);
                    } else {
                        result.complete(value);
                    }
                });
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    private void schedule(Runnable task) {
        if (closed) {
            return;
        }
        try {
            plugin.getServer().getScheduler().runTask(plugin, task);
        } catch (RuntimeException e) {
            // 停用或重载可能与调度竞争；工作线程绝不直接接触玩家状态。
            if (!closed) {
                plugin.getLogger().warning(Lang.console("log.callback_failed"));
            }
        }
    }

    public ActionController actions() {
        return actions;
    }

    public boolean busy(UUID id) {
        Session session = sessions.get(id);
        return session != null && session.requestId != null;
    }

    public boolean configured() {
        return !settings.apiKey().isBlank();
    }

    /** 清除会话、取消在途回合与待确认动作。 */
    public void clear(UUID id) {
        cancelProactive(id);
        Session session = sessions.remove(id);
        if (session != null) {
            session.requestId = null;
            if (session.task != null) {
                session.task.cancel(true);
            }
        }
        workers.purge();
        actions.cancel(id);
    }

    public void clearAll() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            clear(id);
        }
        for (UUID id : List.copyOf(proactiveInFlight.keySet())) cancelProactive(id);
        followUps.clear();
        followUpSet.clear();
        actions.clear();
    }

    /** 清理超时的空闲会话；最多每 30 秒真正执行一次。 */
    public void expire() {
        long now = clock.millis();
        if (now < nextExpiry) {
            return;
        }
        nextExpiry = now + Math.min(EXPIRY_CHECK_MILLIS, settings.sessionTtl().toMillis());
        long cutoff = now - settings.sessionTtl().toMillis();
        List<UUID> expired = sessions.entrySet().stream()
                .filter(e -> e.getValue().requestId == null && e.getValue().touched <= cutoff)
                .map(Map.Entry::getKey)
                .toList();
        expired.forEach(this::clear);
        actions.expire();
    }

    public String status() {
        return "log_dropped=" + logger.droppedEvents() + ", provider=" + settings.provider() + ", model="
                + settings.model() + ", configured=" + configured() + ", active=" + workers.getActiveCount()
                + ", queued=" + workers.getQueue().size() + ", sessions=" + sessions.size() + ", skills="
                + skills.all().size() + ", tools=" + tools.all().size() + ", actions="
                + (settings.commandAwareness() && settings.allowExecution()) + ", runtime="
                + settings.agent().runtime().enabled()
                + ", follow_ups=" + followUps.size() + ", proactive_active=" + proactiveInFlight.size();
    }

    private static void tell(Player player, String key) {
        Lang.send(player, Prefix.PANDA, NamedTextColor.YELLOW, key);
    }

    @Override
    public void close() {
        closed = true;
        clearAll();
        workers.shutdownNow();
        provider.close();
        logger.close();
    }
}
