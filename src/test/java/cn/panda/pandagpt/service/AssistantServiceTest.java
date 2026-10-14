package cn.panda.pandagpt.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.*;
import cn.panda.pandagpt.actions.ActionController;
import cn.panda.pandagpt.agent.*;
import cn.panda.pandagpt.agent.tools.AssistantTools;
import cn.panda.pandagpt.agent.tools.CommandTools;
import cn.panda.pandagpt.ai.*;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.i18n.Msg;
import cn.panda.pandagpt.perception.QuietPolicy;
import cn.panda.pandagpt.util.PandaLogger;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AssistantServiceTest {
    private record Fixture(
            ChatGPTIntegration plugin, Server server, Player player, BlockingQueue<Runnable> callbacks) {}

    /** 按顺序返回预设回复的模型，并记录收到的每个请求。 */
    private static final class ScriptedModel implements AiProvider {
        final List<AiRequest> requests = new CopyOnWriteArrayList<>();
        final Function<AiRequest, AiResponse> script;

        ScriptedModel(Function<AiRequest, AiResponse> script) {
            this.script = script;
        }

        @Override
        public AiResponse generate(AiRequest request) {
            requests.add(request);
            return script.apply(request);
        }
    }

    private static AiResponse call(String name, String arguments) {
        return new AiResponse("", List.of(new ToolCall("call_" + UUID.randomUUID(), name, arguments, null)), null);
    }

    /** 记录按钮回调，替代需要服务器实现的点击回调。 */
    private static final class Buttons implements ActionController.Buttons {
        final List<Consumer<Audience>> clicks = new CopyOnWriteArrayList<>();

        @Override
        public ClickEvent<?> create(Consumer<Audience> onClick, Duration lifetime) {
            clicks.add(onClick);
            return ClickEvent.suggestCommand("button");
        }
    }

    private Fixture fixture() {
        var plugin = mock(ChatGPTIntegration.class);
        var server = mock(Server.class);
        var scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPluginManager()).thenReturn(mock(PluginManager.class));
        when(server.getPluginManager().getPlugins()).thenReturn(new org.bukkit.plugin.Plugin[0]);
        Player p = player(server);
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1));
            return mock(BukkitTask.class);
        });
        return new Fixture(plugin, server, p, callbacks);
    }

    private static Player player(Server server) {
        Player p = mock(Player.class);
        World world = mock(World.class);
        when(world.getEnvironment()).thenReturn(World.Environment.NORMAL);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getName()).thenReturn("Steve");
        when(p.isOnline()).thenReturn(true);
        when(p.hasPermission(anyString())).thenReturn(true);
        when(p.getWorld()).thenReturn(world);
        when(p.getLocation()).thenReturn(new Location(world, 1, 64, 1));
        when(p.getGameMode()).thenReturn(GameMode.SURVIVAL);
        when(p.getInventory()).thenReturn(mock(PlayerInventory.class));
        when(p.getServer()).thenReturn(server);
        when(p.performCommand(anyString())).thenReturn(true);
        when(server.getPlayer(p.getUniqueId())).thenReturn(p);
        return p;
    }

    private static Settings settings(Map<String, Object> overrides) {
        var c = TestSupport.config();
        c.set("gemini.api_key", "test");
        c.set("panda.cooldown", 0);
        overrides.forEach(c::set);
        return Settings.load(c);
    }

    private AssistantService service(
            Fixture f, Settings settings, AiProvider provider, ToolRegistry tools, Buttons buttons) throws Exception {
        var service = new AssistantService(
                f.plugin,
                settings,
                provider,
                mock(PandaLogger.class),
                PromptLibrary.load(null),
                SkillLibrary.load(null, Set.of()),
                tools,
                Clock.systemUTC(),
                buttons,
                p -> "{\"name\":\"" + p.getName() + "\"}");
        when(f.plugin.service()).thenReturn(service);
        when(f.plugin.settings()).thenReturn(settings);
        return service;
    }

    /** 模拟服务器主线程：执行所有已调度的任务，直到一段时间内没有新任务。 */
    private static void pump(Fixture f) throws InterruptedException {
        Runnable task;
        while ((task = f.callbacks.poll(400, TimeUnit.MILLISECONDS)) != null) {
            task.run();
        }
    }

    private static List<String> said(Player player) {
        var captor = ArgumentCaptor.forClass(Component.class);
        verify(player, atLeast(0)).sendMessage(captor.capture());
        return captor.getAllValues().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();
    }

    @Test
    void theAgentCallsToolsOnTheServerThreadAcrossStepsThenAnswers() throws Exception {
        Fixture f = fixture();
        Thread[] toolThread = new Thread[1];
        var registry = new ToolRegistry()
                .register(List.of(AgentTool.of(
                        "get_player_status", "status", Schema.object(), p -> true, null, (context, args) -> {
                            toolThread[0] = Thread.currentThread();
                            return ToolResult.ok("{\"inventory\":{\"IRON_INGOT\":3}}");
                        })));
        var model = new ScriptedModel(request ->
                request.messages().size() == 1 ? call("get_player_status", "{}") : new AiResponse("你有 3 个铁锭。"));
        try (var service = service(f, settings(Map.of()), model, registry, new Buttons())) {
            service.ask(f.player, "我有多少铁？");
            pump(f);
            assertEquals(2, model.requests.size());
            assertSame(Thread.currentThread(), toolThread[0], "tools must run on the scheduler (server) thread");
            var second = model.requests.get(1);
            assertEquals("tool", second.messages().get(2).role());
            assertTrue(second.messages().get(2).content().contains("IRON_INGOT"));
            assertTrue(second.tools().stream()
                    .anyMatch(t -> t.get("name").getAsString().equals("load_skill")));
            assertTrue(second.systemPrompt().contains("Steve"));
            assertTrue(said(f.player).stream().anyMatch(s -> s.contains("你有 3 个铁锭")));
        }
    }

    @Test
    void skillsAreDisclosedOnDemandAndTheCatalogueListsThem() throws Exception {
        Fixture f = fixture();
        var model = new ScriptedModel(request ->
                request.messages().size() == 1 ? call("load_skill", "{\"name\":\"places\"}") : new AiResponse("好的"));
        try (var service = service(f, settings(Map.of()), model, new ToolRegistry(), new Buttons())) {
            service.ask(f.player, "怎么记住地点？");
            pump(f);
            assertTrue(model.requests.getFirst().systemPrompt().contains("- places:"));
            assertTrue(model.requests.get(1).messages().get(2).content().contains("地点记忆"));
        }
    }

    @Test
    void theStepLimitForcesAFinalAnswerWithoutTools() throws Exception {
        Fixture f = fixture();
        var registry = new ToolRegistry()
                .register(List.of(AgentTool.of(
                        "get_player_status",
                        "status",
                        Schema.object(),
                        p -> true,
                        null,
                        (context, args) -> ToolResult.ok("{}"))));
        var model = new ScriptedModel(
                request -> request.finalAnswer() ? new AiResponse("这是我目前知道的。") : call("get_player_status", "{}"));
        try (var service = service(f, settings(Map.of("panda.agent.max_steps", 3)), model, registry, new Buttons())) {
            service.ask(f.player, "一直查");
            pump(f);
            assertEquals(3, model.requests.size());
            assertTrue(model.requests.getLast().finalAnswer());
            assertTrue(said(f.player).stream().anyMatch(s -> s.contains("这是我目前知道的")));
        }
    }

    @Test
    void commandsAreProposedAfterTheReplyAndConfirmedByNaturalLanguage() throws Exception {
        Fixture f = fixture();
        var buttons = new Buttons();
        var settings = settings(Map.of("panda.command_awareness.allow_execution", true));
        var registry = new ToolRegistry();
        AtomicInteger turn = new AtomicInteger();
        var model = new ScriptedModel(request -> {
            boolean first = request.messages().getLast().role().equals("user");
            if (turn.get() == 0) {
                return first
                        ? call("propose_command", "{\"command\":\"help\",\"reason\":\"你要的帮助\"}")
                        : new AiResponse("已准备好，确认后执行。");
            }
            return first ? call("respond_to_pending_action", "{\"decision\":\"confirm\"}") : new AiResponse("好的，已提交。");
        });
        try (var service = service(f, settings, model, registry, buttons)) {
            registry.register(CommandTools.create(f.plugin)).register(AssistantTools.create(f.plugin));
            service.ask(f.player, "帮我打开帮助");
            pump(f);
            verify(f.player, never()).performCommand(anyString());
            assertEquals(2, buttons.clicks.size());
            assertTrue(service.actions().hasPending(f.player.getUniqueId()));
            assertTrue(model.requests.getFirst().tools().stream()
                    .noneMatch(t -> t.get("name").getAsString().equals("respond_to_pending_action")));

            turn.set(1);
            service.ask(f.player, "执行吧");
            pump(f);
            verify(f.player, times(1)).performCommand("help");
            var second = model.requests.get(2);
            assertTrue(second.systemPrompt().contains("[help]"));
            assertTrue(second.tools().stream()
                    .anyMatch(t -> t.get("name").getAsString().equals("respond_to_pending_action")));
        }
    }

    @Test
    void forgettingTheConversationClearsHistoryAfterTheReply() throws Exception {
        Fixture f = fixture();
        var registry = new ToolRegistry();
        var model = new ScriptedModel(
                request -> request.messages().getLast().content().equals("忘掉吧")
                        ? call("forget_conversation", "{}")
                        : new AiResponse("嗯"));
        try (var service = service(f, settings(Map.of()), model, registry, new Buttons())) {
            registry.register(AssistantTools.create(f.plugin));
            service.ask(f.player, "第一句");
            pump(f);
            service.ask(f.player, "忘掉吧");
            pump(f);
            service.ask(f.player, "第三句");
            pump(f);
            assertEquals(1, model.requests.getLast().messages().size());
        }
    }

    @Test
    void concurrentRequestsForOnePlayerAreRejectedAndOfflineCallbacksAreDropped() throws Exception {
        Fixture f = fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AiProvider provider = request -> {
            calls.incrementAndGet();
            entered.countDown();
            release.await();
            return new AiResponse("result");
        };
        try (var service = service(f, settings(Map.of()), provider, new ToolRegistry(), new Buttons())) {
            service.ask(f.player, "first");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.ask(f.player, "second");
            assertEquals(1, calls.get());
            when(f.server.getPlayer(f.player.getUniqueId())).thenReturn(null);
            release.countDown();
            clearInvocations(f.player);
            pump(f);
            verify(f.player, never()).sendMessage(any(Component.class));
        } finally {
            release.countDown();
        }
    }

    @Test
    void clearInvalidatesACompletedButNotYetDeliveredTurn() throws Exception {
        Fixture f = fixture();
        try (var service =
                service(f, settings(Map.of()), request -> new AiResponse("stale"), new ToolRegistry(), new Buttons())) {
            service.ask(f.player, "first");
            Runnable callback = f.callbacks.poll(5, TimeUnit.SECONDS);
            assertNotNull(callback);
            service.clear(f.player.getUniqueId());
            clearInvocations(f.player);
            callback.run();
            verify(f.player, never()).sendMessage(any(Component.class));
        }
    }

    @Test
    void historyIsIsolatedPerPlayerAndTheIdlestSessionIsEvicted() throws Exception {
        Fixture f = fixture();
        var model = new ScriptedModel(request -> new AiResponse("answer"));
        try (var service =
                service(f, settings(Map.of("panda.max_sessions", 2)), model, new ToolRegistry(), new Buttons())) {
            Player second = player(f.server), third = player(f.server);
            service.ask(f.player, "a1");
            pump(f);
            service.ask(f.player, "a2");
            pump(f);
            assertEquals(3, model.requests.getLast().messages().size());
            service.ask(second, "b1");
            pump(f);
            assertEquals(1, model.requests.getLast().messages().size());
            for (int i = 0; i < 3; i++) assertFalse(service.busy(f.player.getUniqueId()));
            service.ask(third, "c1");
            pump(f);
            // 第一位玩家最早提问，已被淘汰，因此再次提问时没有历史。
            service.ask(f.player, "a3");
            pump(f);
            assertEquals(1, model.requests.getLast().messages().size());
        }
    }

    @Test
    void serverQueueIsBoundedAndRejectedRequestsDoNotCollectContext() throws Exception {
        Fixture f = fixture();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AiProvider provider = request -> {
            entered.countDown();
            release.await();
            return new AiResponse("answer");
        };
        var settings = settings(Map.of("panda.requests.workers", 1, "panda.requests.queue_size", 1));
        try (var service = service(f, settings, provider, new ToolRegistry(), new Buttons())) {
            service.ask(f.player, "first");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.ask(player(f.server), "queued");
            Player rejected = player(f.server);
            service.ask(rejected, "overflow");
            assertTrue(service.status().contains("queued=1"));
            verify(rejected, never()).getLocation();
            assertTrue(said(rejected).stream().anyMatch(s -> s.contains("队列已满")));
        } finally {
            release.countDown();
        }
    }

    @Test
    void proactiveTurnsSpeakBrieflyStaySilentOrFallBack() throws Exception {
        Fixture f = fixture();
        var registry = new ToolRegistry();
        var candidate = new QuietPolicy.Candidate(
                QuietPolicy.Kind.READY,
                "ready:IRON_PICKAXE",
                Msg.of("suggest.ready"),
                "IRON_PICKAXE",
                UUID.randomUUID(),
                null,
                Long.MAX_VALUE,
                40,
                false);
        var speak = new ScriptedModel(request -> {
            assertTrue(request.systemPrompt().contains("READY"));
            assertTrue(request.tools().stream()
                    .noneMatch(t -> t.get("name").getAsString().equals("propose_command")));
            return new AiResponse("铁镐的材料齐了，去工作台做吧。");
        });
        try (var service = service(f, settings(Map.of()), speak, registry, new Buttons())) {
            registry.register(AssistantTools.create(f.plugin)).register(CommandTools.create(f.plugin));
            service.proactive(f.player, candidate);
            pump(f);
            assertTrue(said(f.player).stream().anyMatch(s -> s.contains("铁镐的材料齐了")));
        }
        Fixture quiet = fixture();
        var silentRegistry = new ToolRegistry();
        var silent = new ScriptedModel(
                request -> request.messages().size() == 1 ? call("stay_silent", "{}") : new AiResponse(""));
        try (var service = service(quiet, settings(Map.of()), silent, silentRegistry, new Buttons())) {
            silentRegistry.register(AssistantTools.create(quiet.plugin));
            service.proactive(quiet.player, candidate);
            pump(quiet);
            verify(quiet.player, never()).sendMessage(any(Component.class));
        }
        Fixture offline = fixture();
        var noKey = TestSupport.config();
        try (var service = service(
                offline, Settings.load(noKey), request -> fail("must not call"), new ToolRegistry(), new Buttons())) {
            service.proactive(offline.player, candidate);
            assertTrue(said(offline.player).stream().anyMatch(s -> s.contains("目标材料已齐")));
        }
    }
}
