package cn.panda.pandagpt.memory;

import cn.panda.pandagpt.perception.SceneObjects;
import cn.panda.pandagpt.perception.SpatialMemory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 玩家需要时找回正确的那件事：保留原始证据，用四条独立的提取式流程建立可检索记忆。
 * 蒸馏不生成新事实，不将助手回答当作证据；所有处理仅依赖不可变数据，不访问 Bukkit。
 */
public final class GameMemory {
    public enum Kind {
        EPISODE,
        FACT,
        PLAN,
        LESSON
    }

    public enum Source {
        PLAYER,
        SERVER,
        PLACE,
        NOTE,
        GOAL
    }

    public enum Status {
        ACTIVE,
        RESOLVED,
        NEEDS_VERIFICATION
    }

    /** topic 是同一件事的稳定键；world=null 表示跨世界的个人偏好。 */
    public record Event(
            UUID id,
            Kind kind,
            String topic,
            String text,
            List<String> cues,
            Source source,
            UUID world,
            SceneObjects.Pos position,
            long occurredAt,
            long expiresAt,
            Status status) {
        public Event {
            cues = cues == null ? List.of() : List.copyOf(cues);
        }
    }

    /** 蒸馏条目必须能回溯原始证据；时间和作用域来自证据，不由模型推断。 */
    public record Card(String pipeline, UUID evidence, List<UUID> support) {
        public Card {
            support = List.copyOf(support);
        }
    }

    public record State(List<Event> events, List<Card> cards) {
        public State {
            events = events == null ? List.of() : List.copyOf(events);
            cards = cards == null ? List.of() : List.copyOf(cards);
        }

        public static State empty() {
            return new State(List.of(), List.of());
        }
    }

    /** status=historical/expired/resolved/needs_verification 的命中不可用作当前事实。 */
    public record Hit(Event evidence, String pipeline, List<UUID> support, String status, double relevance) {}

    public record Recall(String outcome, List<Hit> matches) {}

    public static final int MAX_PER_KIND = 96;
    public static final int MAX_EVENTS = MAX_PER_KIND * Kind.values().length;
    public static final int MAX_CARDS_PER_PIPELINE = 24;
    public static final int MAX_TEXT = 400;

    private record Topic(Kind kind, String name, UUID world) {}

    /** 每个流程有自己的输入类型、去重规则和输出预算；不消费其他流程的输出。 */
    private interface Pipeline {
        String name();

        Kind kind();

        List<Card> process(List<Event> evidence);
    }

    private record ExtractivePipeline(String name, Kind kind, boolean latestPerTopic) implements Pipeline {
        @Override
        public List<Card> process(List<Event> evidence) {
            var ordered = evidence.stream()
                    .filter(e -> e.kind() == kind)
                    .sorted(NEWEST)
                    .toList();
            Set<Topic> seen = new HashSet<>();
            List<Card> result = new ArrayList<>();
            for (Event event : ordered) {
                if (latestPerTopic && !seen.add(topic(event))) {
                    continue;
                }
                // 完成和过期事件仍参与去重，以免旧计划、旧事实重新成为有效记忆。
                var support = ordered.stream()
                        .filter(e -> topic(e).equals(topic(event))
                                && e.text().equals(event.text())
                                && e.status() == event.status())
                        .map(Event::id)
                        .limit(8)
                        .toList();
                result.add(new Card(name, event.id(), support));
                if (result.size() == MAX_CARDS_PER_PIPELINE) {
                    break;
                }
            }
            return List.copyOf(result);
        }
    }

    private static final Comparator<Event> NEWEST =
            Comparator.comparingLong(Event::occurredAt).reversed();
    private static final List<Pipeline> PIPELINES = List.of(
            new ExtractivePipeline("episodes", Kind.EPISODE, false),
            new ExtractivePipeline("facts", Kind.FACT, true),
            new ExtractivePipeline("plans", Kind.PLAN, true),
            new ExtractivePipeline("lessons", Kind.LESSON, true));

    private static Topic topic(Event event) {
        return new Topic(event.kind(), normalize(event.topic()), event.world());
    }

    public static boolean sameTopic(Event first, Event second) {
        return topic(first).equals(topic(second));
    }

    public static boolean valid(Event e) {
        return e != null
                && e.id() != null
                && e.kind() != null
                && e.source() != null
                && e.status() != null
                && e.topic() != null
                && !e.topic().isBlank()
                && e.topic().length() <= 64
                && e.text() != null
                && !e.text().isBlank()
                && e.text().length() <= MAX_TEXT
                && e.cues().size() <= 8
                && e.cues().stream().allMatch(c -> c != null && !c.isBlank() && c.length() <= 64)
                && (e.position() == null || e.world() != null)
                && e.occurredAt() >= 0
                && (e.expiresAt() == 0 || e.expiresAt() > e.occurredAt());
    }

    public static State append(State state, Event event) {
        if (!valid(event)) {
            throw new IllegalArgumentException("Invalid game memory evidence");
        }
        List<Event> events = new ArrayList<>();
        events.add(event);
        state.events().stream().filter(e -> !e.id().equals(event.id())).forEach(events::add);
        return new State(bound(events), List.of());
    }

    private static List<Event> bound(List<Event> events) {
        // 四类各有配额，高频游戏事件不会挤掉偏好、计划或经验。
        List<Event> result = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        for (Kind kind : Kind.values()) {
            events.stream()
                    .filter(e -> e.kind() == kind)
                    .sorted(NEWEST)
                    .filter(e -> seen.add(e.id()))
                    .limit(MAX_PER_KIND)
                    .forEach(result::add);
        }
        return List.copyOf(result);
    }

    public static State distill(List<Event> events) {
        List<Card> cards = new ArrayList<>();
        for (Pipeline pipeline : PIPELINES) {
            cards.addAll(pipeline.process(events));
        }
        return new State(events, cards);
    }

    /** 将现有地点、笔记与目标接入证据层；每次按原始记录重建，删除后不会由蒸馏复活。 */
    public static State synchronize(SpatialMemory.Data data) {
        return synchronize(data, true);
    }

    public static State synchronize(SpatialMemory.Data data, boolean distilled) {
        List<Event> events = new ArrayList<>(data.game().events().stream()
                .filter(e -> e.source() == Source.PLAYER || e.source() == Source.SERVER)
                .toList());
        for (var place : data.landmarks()) {
            String text = place.name() + " at " + place.position() + "; " + place.state();
            events.add(new Event(
                    stable("place:" + place.world() + ":" + place.name()),
                    Kind.FACT,
                    place.name(),
                    text.substring(0, Math.min(MAX_TEXT, text.length())),
                    SpatialMemory.HOME_SOURCES.contains(place.source())
                            ? List.of("家", "基地", "home", "base")
                            : place.source().equals(SpatialMemory.DEATH_SOURCE)
                                    ? List.of("死亡", "death")
                                    : List.of(place.name()),
                    Source.PLACE,
                    place.world(),
                    place.position(),
                    place.observedAt(),
                    0,
                    place.verifiedAt() == 0 ? Status.NEEDS_VERIFICATION : Status.ACTIVE));
        }
        for (var note : data.notes()) {
            events.add(new Event(
                    stable("note:" + note.createdAt() + ":" + note.text()),
                    note.kind().equals("fact") ? Kind.FACT : note.kind().equals("todo") ? Kind.PLAN : Kind.EPISODE,
                    "note:" + stable("note:" + note.createdAt() + ":" + note.text()),
                    note.text(),
                    List.of(),
                    Source.NOTE,
                    null,
                    null,
                    note.createdAt(),
                    0,
                    Status.ACTIVE));
        }
        if (!data.goal().isBlank()) {
            events.add(new Event(
                    stable("goal:" + data.goal()),
                    Kind.PLAN,
                    "current crafting goal",
                    "Craft " + data.quantity() + " " + data.goal(),
                    List.of("目标", "合成", "goal", "craft"),
                    Source.GOAL,
                    null,
                    null,
                    0,
                    0,
                    Status.ACTIVE));
        }
        List<Event> retained = bound(events);
        return distilled ? distill(retained) : new State(retained, List.of());
    }

    private static UUID stable(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    public static Recall recall(
            State state,
            String query,
            UUID world,
            Kind kind,
            boolean allWorlds,
            boolean history,
            boolean distilled,
            int limit,
            long now) {
        if (query == null || query.isBlank() || query.length() > 200) {
            return new Recall("invalid_query", List.of());
        }
        Map<UUID, Card> cards = new LinkedHashMap<>();
        state.cards().forEach(c -> cards.put(c.evidence(), c));
        Map<Topic, UUID> latest = new LinkedHashMap<>();
        state.events().stream().sorted(NEWEST).forEach(e -> latest.putIfAbsent(topic(e), e.id()));
        List<Hit> hits = new ArrayList<>();
        String needle = normalize(query);
        Set<String> tokens = tokens(needle);
        for (Event event : state.events()) {
            if (kind != null && event.kind() != kind) {
                continue;
            }
            if (!allWorlds && event.world() != null && !event.world().equals(world)) {
                continue;
            }
            String status = event.kind() != Kind.EPISODE && !event.id().equals(latest.get(topic(event)))
                    ? "historical"
                    : event.status() == Status.RESOLVED
                            ? "resolved"
                            : event.expiresAt() != 0 && event.expiresAt() <= now
                                    ? "expired"
                                    : event.status() == Status.NEEDS_VERIFICATION ? "needs_verification" : "active";
            if (!history && (status.equals("historical") || status.equals("resolved") || status.equals("expired"))) {
                continue;
            }
            double score = relevance(needle, tokens, event);
            if (score <= 0) {
                continue;
            }
            Card card = distilled ? cards.get(event.id()) : null;
            hits.add(new Hit(
                    event,
                    card == null ? "raw" : card.pipeline(),
                    card == null ? List.of(event.id()) : card.support(),
                    status,
                    score));
        }
        hits.sort(Comparator.comparingDouble(Hit::relevance)
                .reversed()
                .thenComparing(h -> h.status().equals("active") ? 0 : 1)
                .thenComparing(h -> h.evidence().occurredAt(), Comparator.reverseOrder()));
        List<Hit> matches = hits.stream().limit(Math.clamp(limit, 1, 12)).toList();
        return new Recall(matches.isEmpty() ? "no_match" : "matched", matches);
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).strip();
    }

    /** 英文词与中文二元词组检索；最近时间只打破同分，不能令无关记忆进入结果。 */
    private static double relevance(String needle, Set<String> tokens, Event event) {
        String content = normalize(event.topic() + " " + event.text() + " " + String.join(" ", event.cues()));
        if (containsTerm(content, needle)) {
            return 3 + (double) needle.length() / Math.max(1, content.length());
        }
        long matched =
                tokens.stream().filter(token -> containsTerm(content, token)).count();
        if (matched == 0) {
            return 0;
        }
        double coverage = (double) matched / Math.max(1, tokens.size());
        return coverage < .2 ? 0 : coverage;
    }

    private static boolean containsTerm(String content, String term) {
        if (term.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
            return content.contains(term);
        }
        for (int at = content.indexOf(term); at >= 0; at = content.indexOf(term, at + 1)) {
            int end = at + term.length();
            if ((at == 0 || !Character.isLetterOrDigit(content.charAt(at - 1)))
                    && (end == content.length() || !Character.isLetterOrDigit(content.charAt(end)))) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> tokens(String text) {
        Set<String> result = new HashSet<>();
        for (String part : text.split("[^\\p{L}\\p{N}_]+")) {
            if (part.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN)) {
                int[] chars = part.codePoints().toArray();
                for (int i = 0; i + 1 < chars.length; i++) {
                    result.add(new String(chars, i, 2));
                }
            } else if (part.length() > 1
                    && !Set.of("the", "my", "me", "where", "what", "was", "is", "did", "i")
                            .contains(part)) {
                result.add(part);
            }
        }
        return result;
    }

    public static boolean valid(State state) {
        if (state == null
                || state.events().size() > MAX_EVENTS
                || state.events().stream().anyMatch(e -> !valid(e))) {
            return false;
        }
        Set<UUID> ids = new HashSet<>();
        if (!state.events().stream().allMatch(e -> ids.add(e.id()))) {
            return false;
        }
        if (state.cards().size() > MAX_CARDS_PER_PIPELINE * PIPELINES.size()) {
            return false;
        }
        return state.cards().stream()
                .allMatch(c -> c != null
                        && c.pipeline() != null
                        && ids.contains(c.evidence())
                        && !c.support().isEmpty()
                        && c.support().size() <= 8
                        && c.support().stream().allMatch(ids::contains));
    }

    private GameMemory() {}
}
