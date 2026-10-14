package cn.panda.pandagpt.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ai.*;
import cn.panda.pandagpt.config.AgentSettings;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class AgentRunnerTest {
    private static final AgentSettings SETTINGS = new AgentSettings(6, 4, 30, 50, Set.of(), true);

    private static AiResponse calls(String... names) {
        List<ToolCall> list = new ArrayList<>();
        for (String name : names) {
            list.add(new ToolCall("id-" + name + list.size(), name, "{}", null));
        }
        return new AiResponse("", list, null);
    }

    private static AgentRunner runner(AiProvider provider, Player player) throws Exception {
        AgentRunner.MainThread direct = new AgentRunner.MainThread() {
            @Override
            public <T> CompletableFuture<T> submit(java.util.function.Supplier<CompletableFuture<T>> task) {
                return task.get();
            }
        };
        return new AgentRunner(provider, SETTINGS, SkillLibrary.load(null, Set.of()), direct, id -> player);
    }

    private static Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.isOnline()).thenReturn(true);
        return p;
    }

    @Test
    void parallelCallsAreExecutedInOrderResultsTruncatedAndImagesAttached() throws Exception {
        Player player = player();
        List<AiRequest> seen = new CopyOnWriteArrayList<>();
        AiProvider provider = request -> {
            seen.add(request);
            return seen.size() == 1 ? calls("a", "look", "missing") : new AiResponse("done");
        };
        var image = new AiRequest.Image("image/png", "dGVzdA==");
        Map<String, AgentTool> tools = new LinkedHashMap<>();
        tools.put(
                "a",
                AgentTool.of("a", "a", Schema.object(), p -> true, null, (c, a) -> ToolResult.ok("x".repeat(500))));
        tools.put(
                "look",
                AgentTool.async(
                        "look",
                        "look",
                        Schema.object(),
                        p -> true,
                        null,
                        (c, a) -> CompletableFuture.completedFuture(new ToolResult("saw", image))));
        var turn = new AgentTurn(player.getUniqueId(), AgentTurn.Kind.CONVERSATION);
        var outcome = runner(provider, player)
                .run(new AgentRunner.Plan(turn, "zh_CN", "sys", List.of(Message.user("q")), tools, () -> false));
        assertEquals("done", outcome.text());
        var messages = seen.get(1).messages();
        assertEquals("assistant", messages.get(1).role());
        assertEquals(50, messages.get(2).content().length(), "tool results are truncated");
        assertEquals("saw", messages.get(3).content());
        assertTrue(messages.get(4).content().startsWith("ERROR"), "unknown tools become error results");
        assertSame(image, messages.get(5).image());
        assertEquals(Set.of("a", "look"), turn.toolsUsed());
    }

    @Test
    void toolBudgetAndFailuresAreReportedToTheModelInsteadOfAbortingTheTurn() throws Exception {
        Player player = player();
        List<AiRequest> seen = new CopyOnWriteArrayList<>();
        AiProvider provider = request -> {
            seen.add(request);
            return seen.size() == 1 ? calls("boom", "boom", "boom", "boom", "boom") : new AiResponse("ok");
        };
        Map<String, AgentTool> tools = Map.of(
                "boom",
                AgentTool.async(
                        "boom",
                        "b",
                        Schema.object(),
                        p -> true,
                        null,
                        (c, a) -> CompletableFuture.failedFuture(new IllegalStateException("x"))));
        var outcome = runner(provider, player)
                .run(new AgentRunner.Plan(
                        new AgentTurn(player.getUniqueId(), AgentTurn.Kind.CONVERSATION),
                        "en_US",
                        "sys",
                        List.of(Message.user("q")),
                        tools,
                        () -> false));
        assertEquals("ok", outcome.text());
        var results = seen.get(1).messages().stream()
                .filter(m -> m.role().equals("tool"))
                .toList();
        assertEquals(5, results.size());
        assertTrue(results.get(0).content().contains("Tool failed"));
        assertTrue(results.get(4).content().contains("budget"));
        assertTrue(seen.get(1).finalAnswer(), "an exhausted budget forces the final answer");
    }

    @Test
    void cancellationAndOfflinePlayersStopTheTurn() throws Exception {
        Player player = player();
        AiProvider provider = request -> calls("a");
        Map<String, AgentTool> tools =
                Map.of("a", AgentTool.of("a", "a", Schema.object(), p -> true, null, (c, a) -> ToolResult.ok("x")));
        var turn = new AgentTurn(player.getUniqueId(), AgentTurn.Kind.CONVERSATION);
        assertThrows(
                CancellationException.class,
                () -> runner(provider, player)
                        .run(new AgentRunner.Plan(
                                turn, "en_US", "sys", List.of(Message.user("q")), tools, () -> true)));
        when(player.isOnline()).thenReturn(false);
        assertThrows(
                CancellationException.class,
                () -> runner(provider, player)
                        .run(new AgentRunner.Plan(
                                turn, "en_US", "sys", List.of(Message.user("q")), tools, () -> false)));
    }
}
