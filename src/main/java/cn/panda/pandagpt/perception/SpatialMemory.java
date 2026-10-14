package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.memory.GameMemory;
import cn.panda.pandagpt.memory.MemorySettings;
import cn.panda.pandagpt.memory.SemanticDistiller;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * 按玩家 UUID 隔离、有上限的持久地点记忆与偏好。
 *
 * <p>线程：所有文件读写都在一个有序的单线程 I/O 队列上执行，主线程只读取缓存；坐标索引由 {@code indexLock} 保护。
 */
public final class SpatialMemory implements AutoCloseable {
    /** 一个地点。verifiedAt 为 0 表示该坐标已发生变化，需要重新确认。 */
    public record Landmark(
            String name,
            UUID world,
            SceneObjects.Pos position,
            String state,
            String source,
            long observedAt,
            long verifiedAt) {}

    /**
     * 玩家的一条个人笔记。
     *
     * @param kind todo（待办）、fact（关于玩家的偏好或事实）或 note（普通笔记）
     */
    public record Note(String text, String kind, long createdAt) {}

    /** 一名玩家的完整记忆数据；mode 为 assist 或 quiet。旧文件没有 notes 字段时视为空列表。 */
    public record Data(
            List<Landmark> landmarks, String mode, String goal, int quantity, List<Note> notes, GameMemory.State game) {
        public Data {
            landmarks = List.copyOf(landmarks);
            notes = notes == null ? List.of() : List.copyOf(notes);
            game = game == null ? GameMemory.State.empty() : game;
        }

        public Data(List<Landmark> landmarks, String mode, String goal, int quantity, List<Note> notes) {
            this(landmarks, mode, goal, quantity, notes, GameMemory.State.empty());
        }

        public Data(List<Landmark> landmarks, String mode, String goal, int quantity) {
            this(landmarks, mode, goal, quantity, List.of());
        }

        public Data withLandmarks(List<Landmark> next) {
            return new Data(next, mode, goal, quantity, notes, game);
        }

        public Data withPreferences(String nextMode, String nextGoal, int nextQuantity) {
            return new Data(landmarks, nextMode, nextGoal, nextQuantity, notes, game);
        }

        public Data withNotes(List<Note> next) {
            return new Data(landmarks, mode, goal, quantity, next, game);
        }

        public Data withGame(GameMemory.State next) {
            return new Data(landmarks, mode, goal, quantity, notes, next);
        }

        private static final Data EMPTY = new Data(List.of(), "assist", "", 1, List.of());

        public static Data empty() {
            return EMPTY;
        }
    }

    /** 每名玩家最多保存的地点数。 */
    public static final int MAX_LANDMARKS = 32;

    /** 玩家显式标记的地点来源，淘汰时优先保留。 */
    public static final String EXPLICIT_SOURCE = "explicit_player_mark";

    /** 玩家亲口指定的家。 */
    public static final String HOME_SOURCE = "explicit_home";

    /** 根据重生点自动记住的家。 */
    public static final String AUTO_HOME_SOURCE = "auto_respawn_point";

    /** 家的来源；每个世界只保留一个家，新的家会替换旧的。 */
    public static final Set<String> HOME_SOURCES = Set.of(HOME_SOURCE, AUTO_HOME_SOURCE);

    /** 最近一次死亡的位置；每个世界只保留一个。 */
    public static final String DEATH_SOURCE = "death_point";

    /** 每名玩家最多保存的笔记数。 */
    public static final int MAX_NOTES = 50;

    // 同一组来源在每个世界只保留一个地点。
    private static Set<String> singletonGroup(String source) {
        if (HOME_SOURCES.contains(source)) {
            return HOME_SOURCES;
        }
        return source.equals(DEATH_SOURCE) ? Set.of(DEATH_SOURCE) : Set.of();
    }

    // 淘汰时优先保留的来源。
    private static final Set<String> PROTECTED_SOURCES = Set.of(EXPLICIT_SOURCE, HOME_SOURCE, AUTO_HOME_SOURCE);

    // 队列按配置允许的最大玩家数分配；实际排队上限随当前配置动态限制。
    private static final int MAX_QUEUE = 1024;
    private static final long WARNING_INTERVAL_MILLIS = 60_000;

    private final Path folder;
    private final java.util.function.LongSupplier nanoClock;
    private volatile int limit;
    private volatile MemorySettings settings = MemorySettings.defaults();
    private final Consumer<String> errors;
    private final Map<UUID, Data> cache = new ConcurrentHashMap<>();
    private final Set<UUID> requested = ConcurrentHashMap.newKeySet();
    private final Set<UUID> failed = ConcurrentHashMap.newKeySet();

    private record Key(UUID player, UUID world, SceneObjects.Pos position) {}

    private final Map<Key, Long> invalidations = new ConcurrentHashMap<>();
    private final Map<Key, Integer> pendingLandmarks = new ConcurrentHashMap<>();

    private record Position(UUID world, SceneObjects.Pos pos) {}

    private final Object indexLock = new Object();
    private final Map<Position, Set<Key>> owners = new HashMap<>();
    private final Map<UUID, Set<Key>> indexed = new HashMap<>();
    private final Map<UUID, Long> dirty = new ConcurrentHashMap<>();
    private final Set<UUID> scheduled = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor io;
    private final ThreadPoolExecutor semantic = new ThreadPoolExecutor(
            1,
            1,
            0,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32),
            Thread.ofPlatform().daemon().name("pandagpt-distill-", 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    private volatile SemanticDistiller distiller;
    private final Map<UUID, CompletableFuture<GameMemory.State>> distilling = new ConcurrentHashMap<>();

    private record ForgetPermit(UUID token, List<UUID> evidence, Data snapshot, long deadline) {}

    private final Map<UUID, ForgetPermit> forgetPermits = new ConcurrentHashMap<>();

    public record ForgetPreview(
            UUID token, List<GameMemory.Event> affected, String confirmation, long validForSeconds) {}

    public void setDistiller(SemanticDistiller next) {
        distiller = next;
        forgetPermits.clear();
    }

    private final Gson gson = new Gson();
    private final AtomicLong lastQueueWarning = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressedWarnings = new AtomicLong();

    public SpatialMemory(Path folder, int limit, Consumer<String> errors) {
        this(folder, limit, errors, System::nanoTime);
    }

    public SpatialMemory(Path folder, int limit, Consumer<String> errors, java.util.function.LongSupplier nanoClock) {
        this.nanoClock = nanoClock;
        this.folder = folder;
        this.limit = limit;
        this.errors = errors;
        io = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUE),
                Thread.ofPlatform().daemon().name("pandagpt-memory-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /** 读取缓存视图；首次访问会排队加载文件，加载完成前返回空数据。 */
    public Data view(UUID player) {
        request(player);
        return mask(player, cache.getOrDefault(player, Data.empty()));
    }

    public boolean ready(UUID player) {
        return cache.containsKey(player) && !failed.contains(player);
    }

    /** 重载配置时调整在线玩家上限，同时调整排队上限。 */
    public void setLimit(int limit) {
        this.limit = limit;
    }

    public void configure(MemorySettings next) {
        settings = next;
    }

    public MemorySettings settings() {
        return settings;
    }

    /** 工具必须等待实际落盘后才报告成功；首次加载、失败和队列满均有明确结果。 */
    private <T> CompletableFuture<T> gameTask(UUID player, java.util.function.Function<Data, T> task) {
        CompletableFuture<T> result = new CompletableFuture<>();
        if (!settings.enabled()
                || !request(player)
                || !submit(() -> {
                    try {
                        if (result.isCancelled()) return;
                        if (!ready(player)) {
                            throw new IOException("Memory unavailable");
                        }
                        result.complete(task.apply(mask(player, cache.get(player))));
                    } catch (Exception e) {
                        result.completeExceptionally(e);
                    }
                })) {
            result.completeExceptionally(new IOException("Memory queue unavailable or disabled"));
        }
        return result;
    }

    public CompletableFuture<UUID> rememberGame(UUID player, GameMemory.Event event) {
        if (!GameMemory.valid(event)
                || (event.source() != GameMemory.Source.PLAYER && event.source() != GameMemory.Source.SERVER)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid memory"));
        }
        return gameTask(player, data -> {
            saveGame(player, data.withGame(GameMemory.append(data.game(), event)));
            return event.id();
        });
    }

    private void saveGame(UUID player, Data data) {
        Data previous = cache.getOrDefault(player, Data.empty());
        try {
            Data next = data.withGame(GameMemory.synchronize(data, settings.distillation()));
            write(player, next);
            cache.put(player, mask(player, next));
            index(player, next);
            evidenceChanged(player, previous, next);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private void evidenceChanged(UUID player, Data previous, Data next) {
        if (!previous.game().events().equals(next.game().events())) {
            forgetPermits.remove(player);
            if (settings.distillation() && distiller != null) distillGame(player);
        }
    }

    private static boolean sameEvidenceData(Data first, Data second) {
        return first.landmarks().equals(second.landmarks())
                && first.notes().equals(second.notes())
                && first.goal().equals(second.goal())
                && first.quantity() == second.quantity()
                && first.game().events().equals(second.game().events());
    }

    private static final class StaleEvidence extends IllegalStateException {}

    public CompletableFuture<GameMemory.Recall> recallGame(
            UUID player, UUID world, String query, GameMemory.Kind kind, boolean allWorlds, boolean history, long now) {
        return gameTask(
                player,
                data -> GameMemory.recall(
                        data.game(),
                        query,
                        world,
                        kind,
                        allWorlds,
                        history,
                        settings.distillation(),
                        settings.recallLimit(),
                        now));
    }

    /** Preview and confirmation are scoped to one player, one exact data revision and five minutes. */
    public CompletableFuture<ForgetPreview> prepareForget(UUID player, UUID id) {
        return gameTask(player, data -> prepareForget(player, data, List.of(id)));
    }

    private List<GameMemory.Event> affected(Data data, List<UUID> ids) {
        var selected =
                data.game().events().stream().filter(e -> ids.contains(e.id())).toList();
        if (selected.size() != ids.size() || ids.isEmpty()) throw new IllegalArgumentException("Memory ID not found");
        var affected = data.game().events().stream()
                .filter(e -> ids.contains(e.id())
                        || selected.stream()
                                .anyMatch(s -> s.kind() != GameMemory.Kind.EPISODE && GameMemory.sameTopic(s, e)))
                .toList();
        if (affected.stream().anyMatch(e -> e.source() == GameMemory.Source.GOAL))
            throw new IllegalArgumentException("Use clear_goal for crafting goals");
        return affected;
    }

    private ForgetPreview prepareForget(UUID player, Data data, List<UUID> ids) {
        var affected = affected(data, ids);
        UUID token = UUID.randomUUID();
        forgetPermits.put(
                player,
                new ForgetPermit(token, List.copyOf(ids), data, nanoClock.getAsLong() + TimeUnit.MINUTES.toNanos(5)));
        return new ForgetPreview(token, affected, "确认遗忘 " + token, 300);
    }

    private boolean confirmForget(UUID player, Data data, List<UUID> ids, UUID token, String playerMessage) {
        ForgetPermit permit = forgetPermits.get(player);
        if (permit == null
                || !permit.token().equals(token)
                || !permit.evidence().equals(ids)
                || !("确认遗忘 " + token).equals(playerMessage.strip())
                || nanoClock.getAsLong() >= permit.deadline()
                || !sameEvidenceData(permit.snapshot(), data))
            throw new IllegalArgumentException(
                    "Explicit confirmation missing, expired or memory changed; preview again");
        forgetPermits.remove(player);
        return forgetGame(player, data, ids);
    }

    public CompletableFuture<Boolean> confirmForget(UUID player, UUID id, UUID token, String playerMessage) {
        return gameTask(player, data -> confirmForget(player, data, List.of(id), token, playerMessage));
    }

    /** Legacy place/note tools use exactly the same preview, revision check and atomic deletion path. */
    public CompletableFuture<String> forgetLegacy(
            UUID player, UUID world, String selector, boolean place, UUID token, String playerMessage) {
        return gameTask(player, data -> {
            if (selector.isBlank()) throw new IllegalArgumentException("Empty selector");
            var ids = data.game().events().stream()
                    .filter(e -> place
                            ? e.source() == GameMemory.Source.PLACE
                                    && java.util.Objects.equals(e.world(), world)
                                    && e.topic().equals(selector)
                            : e.source() == GameMemory.Source.NOTE
                                    && e.text()
                                            .toLowerCase(java.util.Locale.ROOT)
                                            .contains(selector.toLowerCase(java.util.Locale.ROOT)))
                    .map(GameMemory.Event::id)
                    .toList();
            if (token == null)
                return "Deletion PREVIEW ONLY; nothing deleted. Show all affected records and request the exact player confirmation: "
                        + gson.toJson(prepareForget(player, data, ids));
            confirmForget(player, data, ids, token, playerMessage);
            return "Memory and linked source records forgotten.";
        });
    }

    public CompletableFuture<Boolean> forgetGame(UUID player, UUID id) {
        return gameTask(
                player,
                data -> data.game().events().stream().noneMatch(e -> e.id().equals(id))
                        ? false
                        : forgetGame(player, data, List.of(id)));
    }

    private boolean forgetGame(UUID player, Data data, List<UUID> ids) {
        var removed = affected(data, ids);
        // 关联来源也必须删除，否则后续蒸馏会从笔记/地点重新生成它。
        Data next = data.withLandmarks(data.landmarks().stream()
                        .filter(l -> removed.stream()
                                .noneMatch(e -> e.source() == GameMemory.Source.PLACE
                                        && l.world().equals(e.world())
                                        && l.name().equals(e.topic())))
                        .toList())
                .withNotes(data.notes().stream()
                        .filter(n -> removed.stream()
                                .noneMatch(e -> e.source() == GameMemory.Source.NOTE
                                        && n.createdAt() == e.occurredAt()
                                        && n.text().equals(e.text())))
                        .toList());
        // 同一事实/计划/经验的旧版本一起删除，防止旧版本重新生效。
        var retained = next.game().events().stream()
                .filter(e -> removed.stream().noneMatch(r -> r.id().equals(e.id())))
                .toList();
        saveGame(player, next.withGame(GameMemory.retain(GameMemory.distill(retained), data.game())));
        forgetPermits.remove(player);
        return true;
    }

    public CompletableFuture<GameMemory.State> distillGame(UUID player) {
        if (!settings.distillation() || distiller == null)
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Semantic distillation unavailable or disabled"));
        // Concurrent requests for one player share a bounded background job.
        synchronized (distilling) {
            var running = distilling.get(player);
            if (running != null) return running;
            CompletableFuture<GameMemory.State> result = new CompletableFuture<>();
            distilling.put(player, result);
            SemanticDistiller current = distiller;
            gameTask(player, Data::game).whenComplete((snapshot, error) -> {
                if (error != null) {
                    result.completeExceptionally(error);
                    return;
                }
                try {
                    semantic.execute(() -> {
                        try {
                            var derived = current.distill(snapshot);
                            gameTask(player, data -> {
                                        if (distiller != current || !settings.distillation())
                                            throw new IllegalStateException("Semantic provider changed or disabled");
                                        if (!data.game().events().equals(snapshot.events())) throw new StaleEvidence();
                                        saveGame(player, data.withGame(derived));
                                        return cache.get(player).game();
                                    })
                                    .whenComplete((state, failure) -> {
                                        if (failure == null) result.complete(state);
                                        else result.completeExceptionally(failure);
                                    });
                        } catch (Exception failure) {
                            result.completeExceptionally(failure);
                        }
                    });
                } catch (RejectedExecutionException failure) {
                    result.completeExceptionally(failure);
                }
            });
            result.whenComplete((state, error) -> {
                distilling.remove(player, result);
                if (error instanceof StaleEvidence && distiller == current && settings.distillation())
                    distillGame(player);
            });
            return result;
        }
    }

    /** 精简上下文只附带与本次需求有关的证据，不把全部历史塞进每次对话。 */
    public String gameContext(UUID player, UUID world, String query, long now) {
        if (!settings.enabled()) {
            return "";
        }
        Data data = view(player);
        if (!ready(player)) {
            return "Game memory loading; call recall_game_memory before claiming past facts.";
        }
        var recall = GameMemory.recall(data.game(), query, world, null, false, false, settings.distillation(), 3, now);
        StringBuilder out = new StringBuilder(
                "Relevant game memories (historical evidence; recheck live state; use recall_game_memory for details):");
        for (var hit : recall.matches()) {
            String line = "\n" + gson.toJson(hit);
            if (out.length() + line.length() > settings.contextChars()) {
                var ref = new JsonObject();
                ref.addProperty("evidence_id", hit.evidence().id().toString());
                ref.addProperty("status", hit.status());
                ref.addProperty(
                        "notice", "Complete evidence omitted for budget; call recall_game_memory before using it.");
                line = "\n" + gson.toJson(ref);
                if (out.length() + line.length() > settings.contextChars()) break;
            }
            out.append(line);
        }
        for (var relation : recall.interpretations()) {
            String line = "\nRelationship interpretation (not verified causation): " + gson.toJson(relation);
            if (out.length() + line.length() <= settings.contextChars()) out.append(line);
        }
        if (recall.matches().isEmpty()) {
            out.append(" none matching this request; do not invent a memory.");
        }
        return out.toString();
    }

    private synchronized boolean request(UUID player) {
        if (requested.contains(player)) {
            return true;
        }
        if (requested.size() >= limit) {
            return false;
        }
        requested.add(player);
        boolean submitted = submit(() -> {
            try {
                Data loaded = read(player);
                cache.put(player, loaded);
                index(player, loaded);
            } catch (Exception e) {
                failed.add(player);
                errors.accept(Lang.console(
                        "log.memory_load_failed", player, e.getClass().getSimpleName()));
            }
        });
        if (!submitted) {
            requested.remove(player);
        }
        return submitted;
    }

    public boolean edit(UUID player, UnaryOperator<Data> update) {
        return edit(player, update, () -> {});
    }

    private boolean edit(UUID player, UnaryOperator<Data> update, Runnable done) {
        if (!request(player)) {
            return false;
        }
        return submit(() -> {
            try {
                // 绝不覆盖无法读取或解析失败的旧文件。
                if (!ready(player)) {
                    return;
                }
                Data previous = mask(player, cache.get(player));
                Data next = mask(player, update.apply(previous));
                next = next.withGame(GameMemory.synchronize(next, settings.distillation()));
                write(player, next);
                cache.put(player, mask(player, next));
                index(player, next);
                evidenceChanged(player, previous, next);
                Set<Key> retained = new HashSet<>();
                for (Landmark l : next.landmarks()) {
                    retained.add(new Key(player, l.world(), l.position()));
                }
                invalidations
                        .keySet()
                        .removeIf(k ->
                                k.player().equals(player) && !retained.contains(k) && !pendingLandmarks.containsKey(k));
            } catch (Exception e) {
                errors.accept(Lang.console(
                        "log.memory_save_failed", player, e.getClass().getSimpleName()));
            } finally {
                done.run();
            }
        });
    }

    /** 记录或更新同名地点；超过上限时优先淘汰自动记录的地点。 */
    public boolean remember(UUID player, Landmark landmark) {
        if (landmark.name().isBlank() || landmark.name().length() > 64) {
            return false;
        }
        Key key = new Key(player, landmark.world(), landmark.position());
        // 写入完成前也要让失效事件找到该坐标。
        synchronized (indexLock) {
            pendingLandmarks.merge(key, 1, Integer::sum);
            addOwner(key);
        }
        Runnable done = () -> {
            synchronized (indexLock) {
                pendingLandmarks.computeIfPresent(key, (k, count) -> count <= 1 ? null : count - 1);
                if (!pendingLandmarks.containsKey(key)
                        && !indexed.getOrDefault(player, Set.of()).contains(key)) {
                    removeOwner(key);
                }
            }
        };
        boolean accepted = edit(
                player,
                data -> {
                    var list = new ArrayList<>(data.landmarks());
                    list.removeIf(l -> l.world().equals(landmark.world())
                            && (l.name().equals(landmark.name())
                                    || singletonGroup(landmark.source()).contains(l.source())));
                    list.add(landmark);
                    while (list.size() > MAX_LANDMARKS) {
                        Landmark automatic = list.stream()
                                .filter(l -> !PROTECTED_SOURCES.contains(l.source()))
                                .findFirst()
                                .orElse(null);
                        if (automatic != null) {
                            list.remove(automatic);
                        } else {
                            list.removeFirst();
                        }
                    }
                    return data.withLandmarks(list);
                },
                done);
        if (!accepted) {
            done.run();
        }
        return accepted;
    }

    /** 添加一条笔记；超出上限时先淘汰最旧的非 fact 笔记。 */
    public CompletableFuture<Boolean> persistNote(UUID player, Note note) {
        if (note.text() == null
                || note.text().isBlank()
                || note.text().length() > 200
                || !Set.of("todo", "fact", "note").contains(note.kind()))
            return CompletableFuture.failedFuture(new IllegalArgumentException("Invalid note"));
        return gameTask(player, data -> {
            var notes = new ArrayList<>(data.notes());
            notes.add(note);
            while (notes.size() > MAX_NOTES) {
                var oldest = notes.stream()
                        .filter(n -> !n.kind().equals("fact"))
                        .findFirst()
                        .orElse(notes.getFirst());
                notes.remove(oldest);
            }
            saveGame(player, data.withNotes(notes));
            return true;
        });
    }

    public boolean addNote(UUID player, Note note) {
        if (note.text().isBlank() || note.text().length() > 200) {
            return false;
        }
        return edit(player, data -> {
            var list = new ArrayList<>(data.notes());
            list.add(note);
            while (list.size() > MAX_NOTES) {
                Note oldest = list.stream()
                        .filter(n -> !n.kind().equals("fact"))
                        .findFirst()
                        .orElse(list.getFirst());
                list.remove(oldest);
            }
            return data.withNotes(list);
        });
    }

    /** 删除匹配的笔记。 */
    public boolean removeNotes(UUID player, java.util.function.Predicate<Note> match) {
        return edit(
                player,
                data -> data.withNotes(
                        data.notes().stream().filter(match.negate()).toList()));
    }

    private void addOwner(Key key) {
        owners.computeIfAbsent(new Position(key.world(), key.position()), p -> new HashSet<>())
                .add(key);
    }

    private void removeOwner(Key key) {
        Position pos = new Position(key.world(), key.position());
        Set<Key> keys = owners.get(pos);
        if (keys != null) {
            keys.remove(key);
            if (keys.isEmpty()) {
                owners.remove(pos);
            }
        }
    }

    private void index(UUID player, Data data) {
        synchronized (indexLock) {
            Set<Key> next = new HashSet<>();
            for (Landmark l : data.landmarks()) {
                next.add(new Key(player, l.world(), l.position()));
            }
            for (Key key : indexed.getOrDefault(player, Set.of())) {
                if (!next.contains(key) && !pendingLandmarks.containsKey(key)) {
                    removeOwner(key);
                }
            }
            for (Key key : next) {
                addOwner(key);
            }
            indexed.put(player, next);
        }
    }

    /** 某坐标发生变化：把记住该坐标的地点标记为待复查。复杂度只与该坐标的记录者数量有关。 */
    public void invalidate(UUID world, SceneObjects.Pos position) {
        Set<UUID> affected = new HashSet<>();
        long now = System.currentTimeMillis();
        synchronized (indexLock) {
            for (Key key : owners.getOrDefault(new Position(world, position), Set.of())) {
                long previous = invalidations.getOrDefault(key, Long.MIN_VALUE);
                boolean fresh = cache.getOrDefault(key.player(), Data.empty()).landmarks().stream()
                        .anyMatch(l -> l.world().equals(world)
                                && l.position().equals(position)
                                && l.verifiedAt() > 0
                                && l.verifiedAt() > previous);
                if (!fresh && !pendingLandmarks.containsKey(key)) {
                    continue;
                }
                invalidations.put(key, now);
                affected.add(key.player());
                dirty.merge(key.player(), 1L, Long::sum);
            }
        }
        for (UUID player : affected) {
            saveInvalidations(player);
        }
    }

    private void saveInvalidations(UUID player) {
        // 每名玩家同时最多一个失效写入任务，即使红石高频触发也不会堆积。
        if (!scheduled.add(player)) {
            return;
        }
        boolean submitted = submit(() -> {
            long revision = dirty.getOrDefault(player, 0L);
            try {
                if (ready(player)) {
                    Data next = mask(player, cache.get(player));
                    write(player, next);
                    cache.put(player, mask(player, next));
                }
            } catch (Exception e) {
                errors.accept(Lang.console(
                        "log.memory_invalidation_failed", e.getClass().getSimpleName()));
            } finally {
                scheduled.remove(player);
                if (dirty.getOrDefault(player, 0L) != revision) {
                    saveInvalidations(player);
                }
            }
        });
        if (!submitted) {
            scheduled.remove(player);
        }
    }

    private Data mask(UUID player, Data data) {
        List<Landmark> changed = null;
        for (int i = 0; i < data.landmarks().size(); i++) {
            Landmark l = data.landmarks().get(i);
            long invalidated = invalidations.getOrDefault(new Key(player, l.world(), l.position()), Long.MIN_VALUE);
            if (l.verifiedAt() > 0 && l.verifiedAt() <= invalidated) {
                if (changed == null) {
                    changed = new ArrayList<>(data.landmarks());
                }
                changed.set(
                        i, new Landmark(l.name(), l.world(), l.position(), l.state(), l.source(), l.observedAt(), 0));
            }
        }
        if (changed == null) {
            return data;
        }
        Data next = data.withLandmarks(changed);
        return next.withGame(GameMemory.synchronize(next, settings.distillation()));
    }

    /** 玩家离线后释放缓存与索引；排在已提交写入之后执行。 */
    public void release(UUID player) {
        submit(() -> {
            synchronized (indexLock) {
                for (Key key : indexed.getOrDefault(player, Set.of())) {
                    removeOwner(key);
                }
                for (Key key : pendingLandmarks.keySet()) {
                    if (key.player().equals(player)) {
                        removeOwner(key);
                    }
                }
                indexed.remove(player);
                dirty.remove(player);
            }
            cache.remove(player);
            failed.remove(player);
            requested.remove(player);
            invalidations.keySet().removeIf(k -> k.player().equals(player));
            pendingLandmarks.keySet().removeIf(k -> k.player().equals(player));
        });
    }

    /** 给模型的历史地点文本，最多 12 条当前世界地点。 */
    public String context(UUID player, UUID world, long now) {
        Data data = view(player);
        if (!ready(player)) {
            return "Spatial memory unavailable or loading; do not infer prior locations.";
        }
        return "Remembered landmarks (historical, not current proof; verifiedAt=0 means invalidated; recheck before "
                + "acting): "
                + gson.toJson(data.landmarks().stream()
                        .filter(l -> l.world().equals(world))
                        .sorted(Comparator.comparingLong(Landmark::observedAt).reversed())
                        .limit(12)
                        .toList())
                + notes(data);
    }

    // 关于玩家的事实与待办放进上下文，让 Agent 记得玩家的偏好与计划。
    private String notes(Data data) {
        if (data.notes().isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("\nPlayer notes (written at the player's request; facts first):");
        data.notes().stream()
                .sorted(Comparator.comparing((Note n) ->
                                n.kind().equals("fact") ? 0 : n.kind().equals("todo") ? 1 : 2)
                        .thenComparing(Comparator.comparingLong(Note::createdAt).reversed()))
                .limit(12)
                .forEach(n -> out.append("\n- [").append(n.kind()).append("] ").append(n.text()));
        return out.toString();
    }

    private boolean submit(Runnable task) {
        if (io.getQueue().size() >= Math.min(MAX_QUEUE, limit * 2)) {
            warnQueueFull();
            return false;
        }
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            warnQueueFull();
            return false;
        }
    }

    // 队列满的警告每分钟最多输出一次，并附带期间被省略的次数，避免刷屏。
    private void warnQueueFull() {
        long now = System.currentTimeMillis();
        long last = lastQueueWarning.get();
        if (last != Long.MIN_VALUE && now - last < WARNING_INTERVAL_MILLIS) {
            suppressedWarnings.incrementAndGet();
            return;
        }
        if (lastQueueWarning.compareAndSet(last, now)) {
            errors.accept(Lang.console("log.memory_queue_full", suppressedWarnings.getAndSet(0)));
        } else {
            suppressedWarnings.incrementAndGet();
        }
    }

    private Data read(UUID player) throws IOException {
        Path path = folder.resolve(player + ".json");
        if (!Files.exists(path)) {
            return Data.empty();
        }
        if (Files.size(path) > 16 * 1048576) {
            throw new IOException("Oversized memory file");
        }
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
        int version = root.get("version").getAsInt();
        if (version != 1 && version != 2 && version != 3) {
            throw new IOException("Unsupported memory schema");
        }
        Data data = gson.fromJson(root.get("data"), Data.class);
        if (data == null
                || data.landmarks().size() > MAX_LANDMARKS
                || !Set.of("quiet", "assist").contains(data.mode())
                || data.goal() == null
                || data.goal().length() > 64
                || data.quantity() < 1
                || data.quantity() > 64
                || data.notes().size() > MAX_NOTES
                || data.notes().stream()
                        .anyMatch(n -> n.text() == null
                                || n.text().isBlank()
                                || n.text().length() > 200
                                || n.kind() == null)
                || !GameMemory.valid(data.game())) {
            throw new IOException("Invalid memory data");
        }
        for (Landmark l : data.landmarks()) {
            if (l.name() == null
                    || l.name().length() > 64
                    || l.world() == null
                    || l.position() == null
                    || l.state() == null
                    || l.state().length() > 512
                    || l.source() == null
                    || l.source().length() > 64) {
                throw new IOException("Invalid landmark");
            }
        }
        return data.withGame(GameMemory.synchronize(data, settings.distillation()));
    }

    // 先写临时文件再原子替换，崩溃时不会留下半个文件。
    private void write(UUID player, Data data) throws IOException {
        Files.createDirectories(folder);
        Path target = folder.resolve(player + ".json");
        Path temp = Files.createTempFile(folder, "memory-", ".tmp");
        JsonObject root = new JsonObject();
        root.addProperty("version", 3);
        root.add("data", gson.toJsonTree(data));
        try {
            Files.writeString(temp, gson.toJson(root), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** 测试与停服使用：等待已提交的写入按顺序完成；I/O 线程不访问 Bukkit。 */
    public boolean drain(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        FutureTask<Void> barrier = new FutureTask<>(() -> null);
        try {
            io.execute(barrier);
        } catch (RejectedExecutionException e) {
            if (io.isShutdown() || !io.getQueue().offer(barrier, millis, TimeUnit.MILLISECONDS)) {
                return false;
            }
        }
        try {
            barrier.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            return true;
        } catch (ExecutionException | TimeoutException e) {
            return false;
        }
    }

    @Override
    public void close() {
        distiller = null;
        forgetPermits.clear();
        semantic.shutdownNow();
        distilling.values().forEach(f -> f.completeExceptionally(new java.util.concurrent.CancellationException()));
        io.shutdown();
        try {
            if (!io.awaitTermination(2, TimeUnit.SECONDS)) {
                errors.accept(Lang.console("log.memory_drain_timeout"));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
