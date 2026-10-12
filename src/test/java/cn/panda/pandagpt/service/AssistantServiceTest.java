package cn.panda.pandagpt.service;

import cn.panda.pandagpt.*;
import cn.panda.pandagpt.ai.*;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.util.PandaLogger;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantServiceTest {
    private record Fixture(ChatGPTIntegration plugin, Server server, Player player, BlockingQueue<Runnable> callbacks) {}
    private Fixture fixture() {
        var plugin = mock(ChatGPTIntegration.class); var server = mock(Server.class); var scheduler = mock(BukkitScheduler.class);
        when(plugin.getServer()).thenReturn(server); when(server.getScheduler()).thenReturn(scheduler);
        Player p = player(); when(server.getPlayer(p.getUniqueId())).thenReturn(p);
        BlockingQueue<Runnable> callbacks = new LinkedBlockingQueue<>();
        when(scheduler.runTask(eq(plugin), any(Runnable.class))).thenAnswer(invocation -> {
            callbacks.add(invocation.getArgument(1)); return mock(BukkitTask.class);
        });
        return new Fixture(plugin, server, p, callbacks);
    }
    private Player player() {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.isOnline()).thenReturn(true); when(p.hasPermission(anyString())).thenReturn(true);
        return p;
    }
    private Settings settings() {
        var c = TestSupport.config(); c.set("gemini.api_key", "test"); c.set("panda.command_awareness.enabled", false);
        c.set("panda.cooldown", 0); return Settings.load(c);
    }
    @Test void concurrentRequestsForOnePlayerAreRejectedAndOfflineCallbacksAreDropped() throws Exception {
        Fixture f = fixture(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        AiProvider provider = request -> { calls.incrementAndGet(); entered.countDown(); release.await(); return new AiResponse("result", List.of()); };
        try (var service = new AssistantService(f.plugin, settings(), provider, mock(PandaLogger.class), null, "", Clock.systemUTC(), (p, q) -> "{}")) {
            service.ask(f.player, "first"); assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.ask(f.player, "second"); assertEquals(1, calls.get());
            when(f.server.getPlayer(f.player.getUniqueId())).thenReturn(null);
            release.countDown();
            Runnable callback = f.callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback);
            clearInvocations(f.player); callback.run(); verify(f.player, never()).sendMessage(any(Component.class));
        } finally { release.countDown(); }
    }
    @Test void clearInvalidatesACompletedButNotYetDeliveredRequest() throws Exception {
        Fixture f = fixture(); AiProvider provider = request -> new AiResponse("stale", List.of());
        try (var service = new AssistantService(f.plugin, settings(), provider, mock(PandaLogger.class), null, "", Clock.systemUTC(), (p, q) -> "{}")) {
            service.ask(f.player, "first");
            Runnable callback = f.callbacks.poll(5, TimeUnit.SECONDS); assertNotNull(callback);
            service.clear(f.player.getUniqueId()); clearInvocations(f.player); callback.run();
            verify(f.player, never()).sendMessage(any(Component.class));
        }
    }
    @Test void contextIsAttachedWithoutPlaceholdersAndHistoryIsIsolatedPerPlayer() throws Exception {
        Fixture f = fixture(); List<AiRequest> received = new CopyOnWriteArrayList<>();
        AiProvider provider = request -> { received.add(request); return new AiResponse("answer", List.of()); };
        try (var service = new AssistantService(f.plugin, settings(), provider, mock(PandaLogger.class), null, "server-rules", Clock.systemUTC(), (p, q) -> "snapshot-value")) {
            service.ask(f.player, "first"); Objects.requireNonNull(f.callbacks.poll(5, TimeUnit.SECONDS)).run();
            service.ask(f.player, "second"); Objects.requireNonNull(f.callbacks.poll(5, TimeUnit.SECONDS)).run();
            Player other = player(); when(f.server.getPlayer(other.getUniqueId())).thenReturn(other);
            service.ask(other, "other"); Objects.requireNonNull(f.callbacks.poll(5, TimeUnit.SECONDS)).run();
            assertTrue(received.get(0).systemPrompt().contains("snapshot-value"));
            assertTrue(received.get(0).systemPrompt().contains("server-rules"));
            assertEquals(3, received.get(1).messages().size()); assertEquals(1, received.get(2).messages().size());
        }
    }
    @Test void serverQueueIsBoundedAndShutdownCancelsWorkers() throws Exception {
        Fixture f = fixture(); CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AiProvider provider = request -> { entered.countDown(); release.await(); return new AiResponse("answer", List.of()); };
        var c = TestSupport.config(); c.set("gemini.api_key", "test"); c.set("panda.command_awareness.enabled", false);
        c.set("panda.requests.workers", 1); c.set("panda.requests.queue_size", 1);
        try (var service = new AssistantService(f.plugin, Settings.load(c), provider, mock(PandaLogger.class), null, "", Clock.systemUTC(), (p, q) -> "")) {
            service.ask(f.player, "first"); assertTrue(entered.await(5, TimeUnit.SECONDS));
            service.ask(player(), "queued"); Player rejected = player(); service.ask(rejected, "overflow");
            assertTrue(service.status().contains("queued=1"));
            var cap = ArgumentCaptor.forClass(Component.class); verify(rejected).sendMessage(cap.capture());
            assertTrue(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(cap.getValue()).contains("队列已满"));
        } finally { release.countDown(); }
    }
}
