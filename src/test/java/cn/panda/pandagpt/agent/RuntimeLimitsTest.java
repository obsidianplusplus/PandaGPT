package cn.panda.pandagpt.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ai.*;
import cn.panda.pandagpt.config.AgentSettings;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class RuntimeLimitsTest {
    private AgentRunner runner(AiProvider provider, int timeout, int budget, Player player) throws Exception {
        return new AgentRunner(
                provider,
                new AgentSettings(4, 8, timeout, 6000, Set.of(), true),
                SkillLibrary.load(null, Set.of()),
                new AgentRunner.MainThread() {
                    public <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> task) {
                        return task.get();
                    }
                },
                id -> player,
                budget);
    }

    private AgentRunner.Plan plan(AgentTurn turn, Map<String, AgentTool> tools, AtomicBoolean cancelled) {
        return new AgentRunner.Plan(turn, "en_US", "system", List.of(Message.user("question")), tools, cancelled::get);
    }

    @Test
    void aSlowProviderIsInterruptedAtTheTurnDeadline() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicInteger requests = new AtomicInteger();
        AiProvider provider = request -> {
            requests.incrementAndGet();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return new AiResponse("late");
        };
        var runner = runner(provider, 1, 6000, null);
        assertThrows(
                TimeoutException.class,
                () -> runner.run(plan(
                        new AgentTurn(UUID.randomUUID(), AgentTurn.Kind.CONVERSATION), Map.of(), new AtomicBoolean())));
        assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        assertEquals(1, requests.get());
    }

    @Test
    void playerCancellationInterruptsAnInFlightModelRequest() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AiProvider provider = request -> {
            entered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                interrupted.countDown();
                throw e;
            }
            return new AiResponse("late");
        };
        var runner = runner(provider, 30, 6000, null);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() ->
                    runner.run(plan(new AgentTurn(UUID.randomUUID(), AgentTurn.Kind.PROACTIVE), Map.of(), cancelled)));
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            cancelled.set(true);
            var error = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void silenceEndsTheTurnWithoutAnotherRequestOrExecutingLaterCalls() throws Exception {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getUniqueId()).thenReturn(id);
        AtomicInteger requests = new AtomicInteger(), writes = new AtomicInteger();
        AiProvider provider = request -> {
            requests.incrementAndGet();
            return new AiResponse(
                    "",
                    List.of(
                            new ToolCall("quiet", "stay_silent", "{}", null),
                            new ToolCall("write", "write", "{}", null)),
                    null);
        };
        var tools = Map.of(
                "stay_silent",
                        AgentTool.of("stay_silent", "quiet", Schema.object(), p -> true, null, (c, a) -> {
                            c.turn().stayQuiet();
                            return ToolResult.ok("silent");
                        }),
                "write",
                        AgentTool.of("write", "write", Schema.object(), p -> true, null, (c, a) -> {
                            writes.incrementAndGet();
                            return ToolResult.ok("bad");
                        }));
        var outcome = runner(provider, 30, 6000, player)
                .run(plan(new AgentTurn(id, AgentTurn.Kind.PROACTIVE), tools, new AtomicBoolean()));
        assertEquals("", outcome.text());
        assertEquals(1, requests.get());
        assertEquals(0, writes.get());
    }

    @Test
    void oversizedToolEvidenceIsReplacedWithAnExplicitErrorWithinTheRemainingContextBudget() throws Exception {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(anyString())).thenReturn(true);
        when(player.getUniqueId()).thenReturn(id);
        List<AiRequest> requests = new CopyOnWriteArrayList<>();
        AiProvider provider = request -> {
            requests.add(request);
            return requests.size() == 1
                    ? new AiResponse("", List.of(new ToolCall("e", "recall_game_memory", "{}", null)), null)
                    : new AiResponse("Need a narrower query");
        };
        var tools = Map.of(
                "recall_game_memory",
                AgentTool.readOnlyOf(
                        "recall_game_memory",
                        "evidence",
                        Schema.object(),
                        p -> true,
                        null,
                        (c, a) -> ToolResult.ok("x".repeat(1500))));
        runner(provider, 30, 1000, player)
                .run(plan(new AgentTurn(id, AgentTurn.Kind.CONVERSATION), tools, new AtomicBoolean()));
        assertEquals(2, requests.size());
        assertTrue(requests.get(1).messages().getLast().content().contains("remaining turn context budget"));
        assertTrue(requests.get(1).messages().stream()
                        .mapToInt(m -> m.content().length())
                        .sum()
                < 1000);
    }
}
