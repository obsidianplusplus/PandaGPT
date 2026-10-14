package cn.panda.pandagpt.memory;

import static org.junit.jupiter.api.Assertions.*;

import cn.panda.pandagpt.perception.SceneObjects;
import cn.panda.pandagpt.perception.SpatialMemory;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GameMemoryTest {
    private GameMemory.Event event(GameMemory.Kind kind, String topic, String text, UUID world, long time) {
        return new GameMemory.Event(
                UUID.randomUUID(),
                kind,
                topic,
                text,
                List.of(),
                GameMemory.Source.PLAYER,
                world,
                null,
                time,
                0,
                GameMemory.Status.ACTIVE);
    }

    @Test
    void independentPipelinesRetainEvidenceAndDoNotMixKindsOrWorlds() {
        UUID world = UUID.randomUUID(), other = UUID.randomUUID();
        var events = List.of(
                event(GameMemory.Kind.EPISODE, "矿洞", "在矿洞找到钻石", world, 10),
                event(GameMemory.Kind.FACT, "建筑风格", "喜欢中式建筑", null, 20),
                event(GameMemory.Kind.PLAN, "铁匠铺", "明天建铁匠铺", world, 30),
                event(GameMemory.Kind.LESSON, "矿洞", "进矿洞要带火把", world, 40),
                event(GameMemory.Kind.FACT, "建筑风格", "这个世界喜欢现代建筑", other, 50));
        var state = GameMemory.distill(events);
        assertEquals(
                Set.of("episodes", "facts", "plans", "lessons"),
                state.cards().stream().map(GameMemory.Card::pipeline).collect(Collectors.toSet()));
        assertEquals(events, state.events());
        assertEquals(5, state.cards().size());
        assertTrue(state.cards().stream().allMatch(c -> c.support().contains(c.evidence())));
        var recall = GameMemory.recall(state, "建筑", world, null, false, false, true, 6, 100);
        assertEquals(1, recall.matches().size());
        assertEquals("喜欢中式建筑", recall.matches().getFirst().evidence().text());
        assertEquals(
                2,
                GameMemory.recall(state, "建筑", world, null, true, false, true, 6, 100)
                        .matches()
                        .size());
    }

    @Test
    void correctionCompletionAndExpiryNeverResurrectSupersededEvidence() {
        UUID world = UUID.randomUUID();
        var old = event(GameMemory.Kind.FACT, "建筑风格", "喜欢中式建筑", null, 10);
        var current = event(GameMemory.Kind.FACT, "建筑风格", "现在喜欢现代建筑", null, 20);
        var plan = event(GameMemory.Kind.PLAN, "铁匠铺", "明天建铁匠铺", world, 30);
        var done = new GameMemory.Event(
                UUID.randomUUID(),
                GameMemory.Kind.PLAN,
                "铁匠铺",
                "铁匠铺建完了",
                List.of(),
                GameMemory.Source.PLAYER,
                world,
                null,
                40,
                0,
                GameMemory.Status.RESOLVED);
        var expired = new GameMemory.Event(
                UUID.randomUUID(),
                GameMemory.Kind.FACT,
                "建筑风格",
                "临时喜欢欧式建筑",
                List.of(),
                GameMemory.Source.PLAYER,
                null,
                null,
                50,
                60,
                GameMemory.Status.ACTIVE);
        var state = GameMemory.distill(List.of(old, current, plan, done, expired));
        assertTrue(GameMemory.recall(state, "建筑", world, null, false, false, true, 6, 70)
                .matches()
                .isEmpty());
        assertTrue(GameMemory.recall(state, "铁匠铺", world, null, false, false, true, 6, 70)
                .matches()
                .isEmpty());
        var history = GameMemory.recall(state, "建筑", world, null, false, true, true, 6, 70);
        assertEquals(3, history.matches().size());
        assertTrue(history.matches().stream().noneMatch(h -> h.status().equals("active")));
        assertEquals(
                "expired",
                history.matches().stream()
                        .filter(h -> h.evidence().id().equals(expired.id()))
                        .findFirst()
                        .orElseThrow()
                        .status());
    }

    @Test
    void retrievalFindsSpecificOlderEvidenceEvenOutsideDistilledCardBudget() {
        var events = new ArrayList<GameMemory.Event>();
        events.add(event(GameMemory.Kind.EPISODE, "钻石", "在山北矿洞找到钻石", null, 1));
        for (int i = 0; i < 50; i++) {
            events.add(event(GameMemory.Kind.EPISODE, "农场", "今天收麦子", null, 10 + i));
        }
        var state = GameMemory.distill(events);
        var recall = GameMemory.recall(state, "钻石", null, null, false, false, true, 6, 100);
        assertEquals(1, recall.matches().size());
        assertEquals("raw", recall.matches().getFirst().pipeline());
        assertEquals(
                "no_match",
                GameMemory.recall(state, "地狱堡垒", null, null, false, false, true, 6, 100)
                        .outcome());
        assertEquals(
                "invalid_query",
                GameMemory.recall(state, "", null, null, false, false, true, 6, 100)
                        .outcome());
    }

    @Test
    void englishAndChineseQueriesMatchConcreteCuesAndRespectCategory() {
        var death = new GameMemory.Event(
                UUID.randomUUID(),
                GameMemory.Kind.EPISODE,
                "死亡 death",
                "Fell into lava",
                List.of("死亡", "掉落", "death", "drops"),
                GameMemory.Source.SERVER,
                null,
                null,
                1,
                0,
                GameMemory.Status.ACTIVE);
        var state = GameMemory.distill(List.of(death));
        assertEquals(
                1,
                GameMemory.recall(state, "我上次死亡", null, null, false, false, true, 6, 10)
                        .matches()
                        .size());
        assertEquals(
                1,
                GameMemory.recall(state, "where was my last death", null, null, false, false, true, 6, 10)
                        .matches()
                        .size());
        assertEquals(
                0,
                GameMemory.recall(state, "death", null, GameMemory.Kind.LESSON, false, false, true, 6, 10)
                        .matches()
                        .size());
        assertEquals(
                0,
                GameMemory.recall(state, "where did I put my emeralds", null, null, false, false, true, 6, 10)
                        .matches()
                        .size());
    }

    @Test
    void episodeStormCannotEvictFactsPlansOrLessons() {
        GameMemory.State state = GameMemory.State.empty();
        for (var kind : List.of(GameMemory.Kind.FACT, GameMemory.Kind.PLAN, GameMemory.Kind.LESSON)) {
            state = GameMemory.append(state, event(kind, "钻石", "钻石装备", null, 1));
        }
        for (int i = 0; i < 200; i++) {
            state = GameMemory.append(state, event(GameMemory.Kind.EPISODE, "农场", "收麦子", null, i + 10));
        }
        assertEquals(GameMemory.MAX_PER_KIND + 3, state.events().size());
        assertEquals(
                3,
                GameMemory.recall(GameMemory.distill(state.events()), "钻石", null, null, false, false, true, 6, 500)
                        .matches()
                        .size());
    }

    @Test
    void legacySourcesAreBoundedAndDeletionDoesNotLeaveDerivedCards() {
        UUID world = UUID.randomUUID();
        var place = new SpatialMemory.Landmark(
                "钻石箱", world, new SceneObjects.Pos(1, 64, 5), "x".repeat(512), SpatialMemory.EXPLICIT_SOURCE, 1, 0);
        var data = new SpatialMemory.Data(
                List.of(place), "assist", "", 1, List.of(new SpatialMemory.Note("喜欢中式建筑", "fact", 1)));
        var state = GameMemory.synchronize(data);
        assertTrue(GameMemory.valid(state));
        assertEquals(
                "needs_verification",
                GameMemory.recall(state, "钻石箱", world, null, false, false, true, 6, 10)
                        .matches()
                        .getFirst()
                        .status());
        var next = data.withGame(state).withNotes(List.of()).withLandmarks(List.of());
        assertTrue(GameMemory.synchronize(next).events().isEmpty());
    }

    @Test
    void englishWordBoundariesDoNotConfuseMineWithMinecraft() {
        var state = GameMemory.distill(List.of(event(GameMemory.Kind.FACT, "chest", "minecraft:chest", null, 1)));
        assertEquals(
                "no_match",
                GameMemory.recall(state, "mine", null, null, false, false, true, 6, 10)
                        .outcome());
    }

    @Test
    void duplicateNotesAndLongCommonPrefixesCannotCorruptOrHideOtherMemories() {
        String prefix = "中".repeat(64);
        var first = new SpatialMemory.Note(prefix + "钻石", "fact", 1);
        var second = new SpatialMemory.Note(prefix + "绿宝石", "fact", 2);
        var data = new SpatialMemory.Data(List.of(), "assist", "", 1, List.of(first, first, second));
        var state = GameMemory.synchronize(data);
        assertTrue(GameMemory.valid(state));
        assertEquals(2, state.events().size());
        assertEquals(
                1,
                GameMemory.recall(state, "钻石", null, null, false, false, true, 6, 10)
                        .matches()
                        .size());
        assertEquals(
                1,
                GameMemory.recall(state, "绿宝石", null, null, false, false, true, 6, 10)
                        .matches()
                        .size());
    }
}
