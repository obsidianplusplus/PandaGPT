package cn.panda.pandagpt.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.ToolContext;
import cn.panda.pandagpt.agent.tools.JournalTools;
import cn.panda.pandagpt.agent.tools.MemoryTools;
import cn.panda.pandagpt.perception.SpatialMemory;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryAuthorizationTest {
    @TempDir
    Path folder;

    private static GameMemory.Event fact(String text, long time) {
        return new GameMemory.Event(
                UUID.randomUUID(),
                GameMemory.Kind.FACT,
                "建筑风格",
                text,
                List.of(),
                GameMemory.Source.PLAYER,
                null,
                null,
                time,
                0,
                GameMemory.Status.ACTIVE);
    }

    @Test
    void previewRequiresExactHumanConfirmationAndTokenIsPlayerIdRevisionExpiryAndOneUseBound() throws Exception {
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        var old = fact("以前喜欢中式", 1);
        var current = fact("现在喜欢现代", 2);
        AtomicLong nanos = new AtomicLong();
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s), nanos::get)) {
            memory.rememberGame(player, old).get(5, TimeUnit.SECONDS);
            memory.rememberGame(player, current).get(5, TimeUnit.SECONDS);
            memory.rememberGame(other, current).get(5, TimeUnit.SECONDS);
            var preview = memory.prepareForget(player, current.id()).get(5, TimeUnit.SECONDS);
            assertEquals(2, preview.affected().size());
            assertEquals(2, memory.view(player).game().events().size());
            assertThrows(
                    ExecutionException.class,
                    () -> memory.confirmForget(player, current.id(), preview.token(), "忘掉吧")
                            .get(5, TimeUnit.SECONDS));
            assertThrows(
                    ExecutionException.class,
                    () -> memory.confirmForget(other, current.id(), preview.token(), preview.confirmation())
                            .get(5, TimeUnit.SECONDS));
            assertThrows(
                    ExecutionException.class,
                    () -> memory.confirmForget(player, old.id(), preview.token(), preview.confirmation())
                            .get(5, TimeUnit.SECONDS));
            nanos.set(TimeUnit.MINUTES.toNanos(5));
            assertThrows(
                    ExecutionException.class,
                    () -> memory.confirmForget(player, current.id(), preview.token(), preview.confirmation())
                            .get(5, TimeUnit.SECONDS));
            var fresh = memory.prepareForget(player, current.id()).get(5, TimeUnit.SECONDS);
            memory.rememberGame(player, fact("又改成欧式", 3)).get(5, TimeUnit.SECONDS);
            assertThrows(
                    ExecutionException.class,
                    () -> memory.confirmForget(player, current.id(), fresh.token(), fresh.confirmation())
                            .get(5, TimeUnit.SECONDS));
            var latest = memory.prepareForget(player, current.id()).get(5, TimeUnit.SECONDS);
            assertTrue(memory.confirmForget(player, current.id(), latest.token(), latest.confirmation())
                    .get(5, TimeUnit.SECONDS));
            assertTrue(memory.view(player).game().events().isEmpty());
            assertEquals(1, memory.view(other).game().events().size());
            assertThrows(
                    ExecutionException.class,
                    () -> memory.confirmForget(player, current.id(), latest.token(), latest.confirmation())
                            .get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void toolsRejectSubstringEvidenceNegationLossProactiveDeletionAndLegacyBypasses() throws Exception {
        var plugin = mock(ChatGPTIntegration.class);
        var player = mock(Player.class);
        var world = mock(World.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        String message = "记住：我不喜欢下界，假如要去才会带抗火药水。";
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            when(plugin.memory()).thenReturn(memory);
            var remember = MemoryTools.create(plugin).getFirst();
            var context = new ToolContext(player, new AgentTurn(id, AgentTurn.Kind.CONVERSATION, message), "zh_CN");
            var args = new JsonObject();
            args.addProperty("kind", "fact");
            args.addProperty("topic", "下界");
            args.addProperty("text", "喜欢下界");
            assertTrue(remember.call(context, args)
                    .get(5, TimeUnit.SECONDS)
                    .content()
                    .startsWith("ERROR"));
            args.addProperty("text", message);
            assertTrue(remember.call(context, args)
                    .get(5, TimeUnit.SECONDS)
                    .content()
                    .contains("persisted"));
            assertEquals(message, memory.view(id).game().events().getFirst().text());
            var forget = MemoryTools.create(plugin).stream()
                    .filter(t -> t.name().equals("forget_game_memory"))
                    .findFirst()
                    .orElseThrow();
            var deletion = new JsonObject();
            deletion.addProperty(
                    "id", memory.view(id).game().events().getFirst().id().toString());
            assertTrue(forget.call(context, deletion)
                    .get(5, TimeUnit.SECONDS)
                    .content()
                    .contains("PREVIEW ONLY"));
            assertEquals(1, memory.view(id).game().events().size());
            var proactive = new ToolContext(player, new AgentTurn(id, AgentTurn.Kind.PROACTIVE, message), "zh_CN");
            assertTrue(forget.call(proactive, deletion)
                    .get(5, TimeUnit.SECONDS)
                    .content()
                    .startsWith("ERROR"));
            memory.addNote(id, new SpatialMemory.Note("下界待办", "todo", 3));
            assertTrue(memory.drain(5000));
            var remove = JournalTools.create(plugin).stream()
                    .filter(t -> t.name().equals("remove_note"))
                    .findFirst()
                    .orElseThrow();
            var noteArgs = new JsonObject();
            noteArgs.addProperty("contains", "下界");
            assertTrue(remove.call(context, noteArgs)
                    .get(5, TimeUnit.SECONDS)
                    .content()
                    .contains("PREVIEW ONLY"));
            assertEquals(1, memory.view(id).notes().size());
            assertTrue(remove.call(proactive, noteArgs)
                    .get(5, TimeUnit.SECONDS)
                    .content()
                    .startsWith("ERROR"));
        }
    }

    @Test
    void longRecallReturnsReferencesAndCanRecoverWholeEvidenceById() throws Exception {
        UUID id = UUID.randomUUID();
        var plugin = mock(ChatGPTIntegration.class);
        var player = mock(Player.class);
        var world = mock(World.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        var first = fact("architecture " + "x".repeat(3400) + " but only if I have time", 1);
        var second = new GameMemory.Event(
                UUID.randomUUID(),
                GameMemory.Kind.EPISODE,
                "trip",
                "architecture " + "y".repeat(3400),
                List.of(),
                GameMemory.Source.PLAYER,
                null,
                null,
                2,
                0,
                GameMemory.Status.ACTIVE);
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            when(plugin.memory()).thenReturn(memory);
            memory.rememberGame(id, first).get(5, TimeUnit.SECONDS);
            memory.rememberGame(id, second).get(5, TimeUnit.SECONDS);
            var recall = MemoryTools.create(plugin).stream()
                    .filter(t -> t.name().equals("recall_game_memory"))
                    .findFirst()
                    .orElseThrow();
            var args = new JsonObject();
            args.addProperty("query", "architecture");
            var context = new ToolContext(player, new AgentTurn(id, AgentTurn.Kind.CONVERSATION), "en_US");
            String refs = recall.call(context, args).get(5, TimeUnit.SECONDS).content();
            assertTrue(refs.contains("evidence_requires_id_lookup"));
            assertTrue(refs.contains(first.id().toString()));
            assertFalse(refs.contains("xxxxx"), "no partial raw quote");
            args.addProperty("query", first.id().toString());
            String full = recall.call(context, args).get(5, TimeUnit.SECONDS).content();
            assertTrue(full.contains(first.text()));
            assertFalse(full.contains("evidence_requires_id_lookup"));
        }
    }

    @Test
    void restartInvalidatesConfirmationPermits() throws Exception {
        UUID player = UUID.randomUUID();
        var event = fact("喜欢中式", 1);
        SpatialMemory.ForgetPreview preview;
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.rememberGame(player, event).get(5, TimeUnit.SECONDS);
            preview = memory.prepareForget(player, event.id()).get(5, TimeUnit.SECONDS);
        }
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            assertThrows(
                    ExecutionException.class,
                    () -> memory.confirmForget(player, event.id(), preview.token(), preview.confirmation())
                            .get(5, TimeUnit.SECONDS));
            assertEquals(1, memory.view(player).game().events().size());
        }
    }
}
