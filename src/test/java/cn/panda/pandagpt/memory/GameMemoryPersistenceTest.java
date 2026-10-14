package cn.panda.pandagpt.memory;

import static org.junit.jupiter.api.Assertions.*;

import cn.panda.pandagpt.perception.SceneObjects;
import cn.panda.pandagpt.perception.SpatialMemory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameMemoryPersistenceTest {
    @TempDir
    Path folder;

    private GameMemory.Event fact(String text, long time) {
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
    void survivesRestartAndIsolatesPlayersAndForgottenVersions() throws Exception {
        UUID player = UUID.randomUUID(), other = UUID.randomUUID();
        UUID newest;
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.rememberGame(player, fact("喜欢中式建筑", 1)).get(5, TimeUnit.SECONDS);
            newest = memory.rememberGame(player, fact("现在喜欢现代建筑", 2)).get(5, TimeUnit.SECONDS);
            memory.rememberGame(other, fact("喜欢欧式建筑", 3)).get(5, TimeUnit.SECONDS);
        }
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            var recalled = memory.recallGame(player, null, "建筑", null, false, false, 10)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(1, recalled.matches().size());
            assertEquals(newest, recalled.matches().getFirst().evidence().id());
            assertTrue(memory.forgetGame(player, newest).get(5, TimeUnit.SECONDS));
            memory.setDistiller(new SemanticDistiller(
                    request -> new cn.panda.pandagpt.ai.AiResponse("{\"meanings\":[],\"relations\":[]}")));
            memory.distillGame(player).get(5, TimeUnit.SECONDS);
            assertEquals(
                    "no_match",
                    memory.recallGame(player, null, "建筑", null, true, true, 10)
                            .get(5, TimeUnit.SECONDS)
                            .outcome());
            assertEquals(
                    "喜欢欧式建筑",
                    memory.recallGame(other, null, "建筑", null, false, false, 10)
                            .get(5, TimeUnit.SECONDS)
                            .matches()
                            .getFirst()
                            .evidence()
                            .text());
        }
    }

    @Test
    void migratesV1AndForgetsLinkedNoteAcrossRestart() throws Exception {
        UUID player = UUID.randomUUID();
        Files.writeString(folder.resolve(player + ".json"), """
                {"version":1,"data":{"landmarks":[],"mode":"assist","goal":"","quantity":1,
                "notes":[{"text":"喜欢中式建筑","kind":"fact","createdAt":1}]}}
                """);
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            var result = memory.recallGame(player, null, "中式", null, false, false, 10)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(
                    GameMemory.Source.NOTE,
                    result.matches().getFirst().evidence().source());
            assertTrue(memory.forgetGame(
                            player, result.matches().getFirst().evidence().id())
                    .get(5, TimeUnit.SECONDS));
            assertTrue(memory.view(player).notes().isEmpty());
        }
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            assertEquals(
                    "no_match",
                    memory.recallGame(player, null, "中式", null, false, true, 10)
                            .get(5, TimeUnit.SECONDS)
                            .outcome());
        }
    }

    @Test
    void changedLandmarkIsImmediatelyRecalledAsUnverifiedAndForgetRemovesItsSource() throws Exception {
        UUID player = UUID.randomUUID(), world = UUID.randomUUID();
        var pos = new SceneObjects.Pos(1, 64, 5);
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.remember(
                    player,
                    new SpatialMemory.Landmark(
                            "钻石箱", world, pos, "minecraft:chest", SpatialMemory.EXPLICIT_SOURCE, 1, 1));
            assertTrue(memory.drain(5000));
            memory.invalidate(world, pos);
            var recall = memory.recallGame(player, world, "钻石箱", null, false, false, 10)
                    .get(5, TimeUnit.SECONDS);
            assertEquals("needs_verification", recall.matches().getFirst().status());
            assertTrue(memory.forgetGame(
                            player, recall.matches().getFirst().evidence().id())
                    .get(5, TimeUnit.SECONDS));
            memory.setDistiller(new SemanticDistiller(
                    request -> new cn.panda.pandagpt.ai.AiResponse("{\"meanings\":[],\"relations\":[]}")));
            memory.distillGame(player).get(5, TimeUnit.SECONDS);
            assertTrue(memory.view(player).landmarks().isEmpty());
            assertEquals(
                    "no_match",
                    memory.recallGame(player, world, "钻石箱", null, false, true, 10)
                            .get(5, TimeUnit.SECONDS)
                            .outcome());
        }
    }

    @Test
    void corruptOrFutureSchemaIsNeverOverwrittenAndFailedWriteDoesNotReportSuccess() throws Exception {
        UUID player = UUID.randomUUID();
        String corrupt = "{\"version\":99,\"data\":{}}";
        Files.writeString(folder.resolve(player + ".json"), corrupt);
        var errors = new CopyOnWriteArrayList<String>();
        try (var memory = new SpatialMemory(folder, 4, errors::add)) {
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> memory.rememberGame(player, fact("喜欢中式建筑", 1)).get(5, TimeUnit.SECONDS));
            assertEquals(corrupt, Files.readString(folder.resolve(player + ".json")));
        }
        assertFalse(errors.isEmpty());
        Path fileInsteadOfFolder = folder.resolve("not-a-folder");
        Files.writeString(fileInsteadOfFolder, "sentinel");
        try (var memory = new SpatialMemory(fileInsteadOfFolder, 4, errors::add)) {
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> memory.rememberGame(player, fact("喜欢中式建筑", 1)).get(5, TimeUnit.SECONDS));
            assertEquals("sentinel", Files.readString(fileInsteadOfFolder));
            assertTrue(memory.view(player).game().events().isEmpty());
        }
    }

    @Test
    void configurationDisablesMemoryOrDistillationWithoutLosingEvidence() throws Exception {
        UUID player = UUID.randomUUID();
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.configure(new MemorySettings(true, false, 6, 500));
            memory.rememberGame(player, fact("喜欢中式建筑", 1)).get(5, TimeUnit.SECONDS);
            assertTrue(memory.view(player).game().cards().isEmpty());
            assertEquals(
                    "raw",
                    memory.recallGame(player, null, "中式", null, false, false, 10)
                            .get(5, TimeUnit.SECONDS)
                            .matches()
                            .getFirst()
                            .pipeline());
            memory.configure(new MemorySettings(false, false, 6, 500));
            assertEquals("", memory.gameContext(player, null, "中式", 10));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> memory.recallGame(player, null, "中式", null, false, false, 10)
                            .get(5, TimeUnit.SECONDS));
            memory.configure(MemorySettings.defaults());
            memory.setDistiller(new SemanticDistiller(
                    request -> new cn.panda.pandagpt.ai.AiResponse("{\"meanings\":[],\"relations\":[]}")));
            memory.distillGame(player).get(5, TimeUnit.SECONDS);
            assertEquals(
                    "facts",
                    memory.recallGame(player, null, "中式", null, false, false, 10)
                            .get(5, TimeUnit.SECONDS)
                            .matches()
                            .getFirst()
                            .pipeline());
            assertTrue(memory.gameContext(player, null, "中式", 10).length()
                    <= memory.settings().contextChars());
        }
    }
}
