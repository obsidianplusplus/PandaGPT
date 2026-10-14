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

class ReadConcurrencyTest {
    private static Player player() {
        var player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        return player;
    }

    private static AgentRunner runner(Player player, AiProvider provider, int cap, int timeout) throws Exception {
        return new AgentRunner(
                provider,
                new AgentSettings(4, 12, timeout, 6000, Set.of(), true, cap),
                SkillLibrary.load(null, Set.of()),
                new AgentRunner.MainThread() {
                    public <T> CompletableFuture<T> submit(Supplier<CompletableFuture<T>> task) {
                        return task.get();
                    }
                },
                id -> player);
    }

    private static AiProvider provider(List<AiRequest> seen, String... names) {
        return request -> {
            seen.add(request);
            if (seen.size() > 1) return new AiResponse("done");
            var calls = new ArrayList<ToolCall>();
            for (int i = 0; i < names.length; i++) calls.add(new ToolCall("call-" + i, names[i], "{}", null));
            return new AiResponse("", calls, null);
        };
    }

    @Test
    void boundedReadsOverlapResultsKeepOrderAndWritesAreBarriers() throws Exception {
        var player = player();
        var seen = new CopyOnWriteArrayList<AiRequest>();
        var started = new CopyOnWriteArrayList<String>();
        var first = new CompletableFuture<ToolResult>();
        var second = new CompletableFuture<ToolResult>();
        var twoReads = new CountDownLatch(2);
        Map<String, AgentTool> tools = new LinkedHashMap<>();
        tools.put("a", AgentTool.readOnlyAsync("a", "a", Schema.object(), p -> true, null, (c, a) -> {
            started.add("a");
            twoReads.countDown();
            return first;
        }));
        tools.put("b", AgentTool.readOnlyAsync("b", "b", Schema.object(), p -> true, null, (c, a) -> {
            started.add("b");
            twoReads.countDown();
            return second;
        }));
        tools.put("c", AgentTool.readOnlyOf("c", "c", Schema.object(), p -> true, null, (c, a) -> {
            assertTrue(first.isDone() && second.isDone());
            started.add("c");
            return ToolResult.ok("third");
        }));
        tools.put("write", AgentTool.of("write", "write", Schema.object(), p -> true, null, (c, a) -> {
            assertEquals(List.of("a", "b", "c"), started);
            started.add("write");
            return ToolResult.ok("written");
        }));
        tools.put("after", AgentTool.readOnlyOf("after", "after", Schema.object(), p -> true, null, (c, a) -> {
            assertTrue(started.contains("write"));
            started.add("after");
            return ToolResult.ok("last");
        }));
        var runner = runner(player, provider(seen, "a", "b", "c", "write", "after"), 2, 10);
        var plan = new AgentRunner.Plan(
                new AgentTurn(player.getUniqueId(), AgentTurn.Kind.CONVERSATION),
                "en_US",
                "s",
                List.of(Message.user("q")),
                tools,
                () -> false);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> runner.run(plan));
            try {
                assertTrue(twoReads.await(5, TimeUnit.SECONDS), "second read must start before first finishes");
                assertEquals(List.of("a", "b"), started, "cap of two and write barrier");
                second.complete(ToolResult.ok("second"));
                assertEquals(List.of("a", "b"), started);
                first.complete(ToolResult.ok("first"));
                assertEquals("done", task.get(5, TimeUnit.SECONDS).text());
            } finally {
                first.complete(ToolResult.ok("released"));
                second.complete(ToolResult.ok("released"));
            }
        }
        var results = seen.get(1).messages().stream()
                .filter(m -> m.role().equals("tool"))
                .map(Message::content)
                .toList();
        assertEquals(List.of("first", "second", "third", "written", "last"), results);
    }

    @Test
    void cancellationCancelsEveryPendingReadBeforeAnyWriteStarts() throws Exception {
        var player = player();
        var seen = new CopyOnWriteArrayList<AiRequest>();
        var pending = List.of(new CompletableFuture<ToolResult>(), new CompletableFuture<ToolResult>());
        var started = new CountDownLatch(2);
        var cancelled = new AtomicBoolean();
        var write = new AtomicBoolean();
        Map<String, AgentTool> tools = new HashMap<>();
        for (int i = 0; i < 2; i++) {
            final int index = i;
            tools.put("r" + i, AgentTool.readOnlyAsync("r" + i, "r", Schema.object(), p -> true, null, (c, a) -> {
                started.countDown();
                return pending.get(index);
            }));
        }
        tools.put("w", AgentTool.of("w", "w", Schema.object(), p -> true, null, (c, a) -> {
            write.set(true);
            return ToolResult.ok("w");
        }));
        var runner = runner(player, provider(seen, "r0", "r1", "w"), 2, 10);
        var plan = new AgentRunner.Plan(
                new AgentTurn(player.getUniqueId(), AgentTurn.Kind.CONVERSATION),
                "en_US",
                "s",
                List.of(),
                tools,
                cancelled::get);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var task = executor.submit(() -> runner.run(plan));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            cancelled.set(true);
            var error = assertThrows(ExecutionException.class, () -> task.get(5, TimeUnit.SECONDS));
            assertInstanceOf(CancellationException.class, error.getCause());
        }
        assertTrue(pending.stream().allMatch(CompletableFuture::isCancelled));
        assertFalse(write.get());
    }

    @Test
    void oneFailedReadDoesNotLoseOtherResultsAndDeadlinePreventsLateWrites() throws Exception {
        var player = player();
        var seen = new CopyOnWriteArrayList<AiRequest>();
        var hanging = new CompletableFuture<ToolResult>();
        var writes = new AtomicInteger();
        var tools = Map.of(
                "bad",
                        AgentTool.readOnlyAsync(
                                "bad",
                                "b",
                                Schema.object(),
                                p -> true,
                                null,
                                (c, a) -> CompletableFuture.failedFuture(new IllegalStateException())),
                "good",
                        AgentTool.readOnlyOf(
                                "good", "g", Schema.object(), p -> true, null, (c, a) -> ToolResult.ok("ok")),
                "hang", AgentTool.readOnlyAsync("hang", "h", Schema.object(), p -> true, null, (c, a) -> hanging),
                "write",
                        AgentTool.of("write", "w", Schema.object(), p -> true, null, (c, a) -> {
                            writes.incrementAndGet();
                            return ToolResult.ok("w");
                        }));
        runner(player, provider(seen, "bad", "good", "hang", "write"), 3, 1)
                .run(new AgentRunner.Plan(
                        new AgentTurn(player.getUniqueId(), AgentTurn.Kind.CONVERSATION),
                        "en_US",
                        "s",
                        List.of(),
                        tools,
                        () -> false));
        var results = seen.get(1).messages().stream()
                .filter(m -> m.role().equals("tool"))
                .map(Message::content)
                .toList();
        assertTrue(results.get(0).contains("Tool failed"));
        assertEquals("ok", results.get(1));
        assertTrue(results.get(2).contains("timed out"));
        assertEquals(0, writes.get());
        assertTrue(hanging.isCancelled());
    }
}
