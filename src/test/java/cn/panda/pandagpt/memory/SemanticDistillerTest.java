package cn.panda.pandagpt.memory;

import static org.junit.jupiter.api.Assertions.*;

import cn.panda.pandagpt.ai.AiResponse;
import cn.panda.pandagpt.perception.SpatialMemory;
import com.google.gson.Gson;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SemanticDistillerTest {
    @TempDir
    Path folder;

    private static final Gson JSON = new Gson();

    private static GameMemory.Event event(GameMemory.Kind kind, String text, UUID world, long time) {
        return new GameMemory.Event(
                UUID.randomUUID(),
                kind,
                "trip-" + time,
                text,
                List.of(),
                GameMemory.Source.PLAYER,
                world,
                null,
                time,
                0,
                GameMemory.Status.ACTIVE);
    }

    private static String output(GameMemory.Meaning meaning, GameMemory.Relation relation) {
        var root = new com.google.gson.JsonObject();
        root.add("meanings", JSON.toJsonTree(List.of(meaning)));
        root.add("relations", JSON.toJsonTree(List.of(relation)));
        return JSON.toJson(root);
    }

    @Test
    void semanticRetrievalConnectsDistinctExperiencesAndPreservesEvidenceAcrossRestart() throws Exception {
        UUID world = UUID.randomUUID(), player = UUID.randomUUID();
        var death = event(GameMemory.Kind.EPISODE, "昨天掉进岩浆丢了装备", world, 1);
        var plan = event(GameMemory.Kind.PLAN, "下次带抗火药水再去找残骸", world, 2);
        var meaning =
                new GameMemory.Meaning(death.id(), "一次下界探险损失，关联到后来降低风险的准备计划", List.of(death.id()), List.of("安全探索"));
        var relation = new GameMemory.Relation(
                death.id(), plan.id(), GameMemory.RelationType.INFORMS_PLAN, "后来计划增加抗火准备；这是关联解释，不能证明玩家当时的心理动机");
        var raw = GameMemory.distill(List.of(death, plan));
        var distiller = new SemanticDistiller(request -> {
            assertTrue(request.tools().isEmpty());
            assertTrue(request.finalAnswer());
            assertEquals(4096, request.maxOutputTokens());
            assertTrue(request.messages().getFirst().content().contains(death.text()));
            assertFalse(
                    request.messages().getFirst().content().contains(meaning.summary()),
                    "do not feed summaries back as evidence");
            return new AiResponse(output(meaning, relation));
        });
        var state = distiller.distill(raw);
        assertEquals(raw.events(), state.events());
        var recall = GameMemory.recall(state, "安全探索", world, null, false, false, true, 6, 10);
        assertEquals(2, recall.matches().size());
        assertEquals(death.text(), recall.matches().getFirst().evidence().text());
        assertEquals(meaning, recall.matches().getFirst().interpretation());
        assertEquals(List.of(relation), recall.interpretations());
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.rememberGame(player, death).get(5, TimeUnit.SECONDS);
            memory.rememberGame(player, plan).get(5, TimeUnit.SECONDS);
            memory.setDistiller(distiller);
            memory.distillGame(player).get(5, TimeUnit.SECONDS);
        }
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            var loaded = memory.recallGame(player, world, "安全探索", null, false, false, 10)
                    .get(5, TimeUnit.SECONDS);
            assertEquals(List.of(relation), loaded.interpretations());
            var preview = memory.prepareForget(player, death.id()).get(5, TimeUnit.SECONDS);
            assertTrue(memory.confirmForget(player, death.id(), preview.token(), preview.confirmation())
                    .get(5, TimeUnit.SECONDS));
            assertTrue(memory.view(player).game().meanings().isEmpty());
            assertTrue(memory.view(player).game().relations().isEmpty());
            assertEquals(List.of(plan), memory.view(player).game().events());
        }
    }

    @Test
    void invalidReferencesAndCrossWorldSummarySupportCannotBeCommitted() {
        var first = event(GameMemory.Kind.EPISODE, "失去装备", UUID.randomUUID(), 1);
        var second = event(GameMemory.Kind.PLAN, "计划出发", UUID.randomUUID(), 2);
        var state = GameMemory.distill(List.of(first, second));
        var foreign = new GameMemory.Meaning(first.id(), "语义", List.of(first.id(), UUID.randomUUID()), List.of());
        assertThrows(IllegalArgumentException.class, () -> GameMemory.semantic(state, List.of(foreign), List.of()));
        var crossWorld = new GameMemory.Meaning(first.id(), "语义", List.of(first.id(), second.id()), List.of());
        assertThrows(IllegalArgumentException.class, () -> GameMemory.semantic(state, List.of(crossWorld), List.of()));
        var relation = new GameMemory.Relation(first.id(), second.id(), GameMemory.RelationType.CONTRAST, "两个世界的经历对比");
        var linked = GameMemory.semantic(state, List.of(), List.of(relation));
        assertTrue(GameMemory.recall(linked, "失去", first.world(), null, false, false, true, 6, 10)
                .interpretations()
                .isEmpty());
        assertEquals(
                2,
                GameMemory.recall(linked, "失去", first.world(), null, true, false, true, 6, 10)
                        .matches()
                        .size());
    }

    @Test
    void typedRelationshipsRejectWrongDirectionAndExactEvidenceLookupDoesNotExpand() {
        var earlier = event(GameMemory.Kind.EPISODE, "lava", null, 1);
        var plan = event(GameMemory.Kind.PLAN, "bring potion", null, 2);
        var state = GameMemory.distill(List.of(earlier, plan));
        var invalid =
                new GameMemory.Relation(plan.id(), earlier.id(), GameMemory.RelationType.INFORMS_PLAN, "backwards");
        assertThrows(IllegalArgumentException.class, () -> GameMemory.semantic(state, List.of(), List.of(invalid)));
        var invalidOutcome = new GameMemory.Relation(
                earlier.id(), plan.id(), GameMemory.RelationType.PLAN_OUTCOME, "not a plan outcome");
        assertThrows(
                IllegalArgumentException.class, () -> GameMemory.semantic(state, List.of(), List.of(invalidOutcome)));
        var link = new GameMemory.Relation(
                earlier.id(), plan.id(), GameMemory.RelationType.INFORMS_PLAN, "preparation informed by experience");
        var linked = GameMemory.semantic(state, List.of(), List.of(link));
        var direct = GameMemory.recall(linked, earlier.id().toString(), null, null, false, false, true, 6, 10);
        assertEquals(1, direct.matches().size());
        assertEquals(earlier, direct.matches().getFirst().evidence());
        assertTrue(direct.interpretations().isEmpty());
    }

    @Test
    void malformedModelOutputPreservesPreviouslyCommittedEvidenceAndInterpretations() throws Exception {
        UUID player = UUID.randomUUID();
        var raw = event(GameMemory.Kind.EPISODE, "不喜欢下界，只是假如要去才会带抗火药水", null, 1);
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.rememberGame(player, raw).get(5, TimeUnit.SECONDS);
            var before = memory.view(player).game();
            memory.setDistiller(new SemanticDistiller(request -> new AiResponse("not JSON")));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> memory.distillGame(player).get(5, TimeUnit.SECONDS));
            assertEquals(before, memory.view(player).game());
        }
    }

    @Test
    void deletionDuringModelRequestCannotResurrectEvidence() throws Exception {
        UUID player = UUID.randomUUID();
        var first = event(GameMemory.Kind.EPISODE, "经历一", null, 1);
        var second = event(GameMemory.Kind.EPISODE, "经历二", null, 2);
        var meaning = new GameMemory.Meaning(first.id(), "摘要", List.of(first.id()), List.of("回忆"));
        var relation = new GameMemory.Relation(first.id(), second.id(), GameMemory.RelationType.CONTINUATION, "延续");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicBoolean initial = new AtomicBoolean(true);
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            memory.rememberGame(player, first).get(5, TimeUnit.SECONDS);
            memory.rememberGame(player, second).get(5, TimeUnit.SECONDS);
            memory.setDistiller(new SemanticDistiller(request -> {
                if (!initial.getAndSet(false)) return new AiResponse("{\"meanings\":[],\"relations\":[]}");
                entered.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return new AiResponse(output(meaning, relation));
            }));
            var pending = memory.distillGame(player);
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var preview = memory.prepareForget(player, first.id()).get(5, TimeUnit.SECONDS);
                memory.confirmForget(player, first.id(), preview.token(), preview.confirmation())
                        .get(5, TimeUnit.SECONDS);
            } finally {
                release.countDown();
            }
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
            memory.distillGame(player).get(5, TimeUnit.SECONDS);
            assertEquals(List.of(second), memory.view(player).game().events());
            assertTrue(memory.view(player).game().meanings().isEmpty());
        }
    }
}
