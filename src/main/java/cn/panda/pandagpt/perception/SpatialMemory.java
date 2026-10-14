package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.i18n.Lang;
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

    /** 一名玩家的完整记忆数据；mode 为 assist 或 quiet。 */
    public record Data(List<Landmark> landmarks, String mode, String goal, int quantity) {
        public Data {
            landmarks = List.copyOf(landmarks);
        }

        private static final Data EMPTY = new Data(List.of(), "assist", "", 1);

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

    // 淘汰时优先保留的来源。
    private static final Set<String> PROTECTED_SOURCES = Set.of(EXPLICIT_SOURCE, HOME_SOURCE, AUTO_HOME_SOURCE);

    // 队列按配置允许的最大玩家数分配；实际排队上限随当前配置动态限制。
    private static final int MAX_QUEUE = 1024;
    private static final long WARNING_INTERVAL_MILLIS = 60_000;

    private final Path folder;
    private volatile int limit;
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
    private final Gson gson = new Gson();
    private final AtomicLong lastQueueWarning = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong suppressedWarnings = new AtomicLong();

    public SpatialMemory(Path folder, int limit, Consumer<String> errors) {
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
                Data next = mask(player, update.apply(mask(player, cache.get(player))));
                write(player, next);
                cache.put(player, mask(player, next));
                index(player, next);
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
                                    || (HOME_SOURCES.contains(landmark.source())
                                            && HOME_SOURCES.contains(l.source()))));
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
                    return new Data(list, data.mode(), data.goal(), data.quantity());
                },
                done);
        if (!accepted) {
            done.run();
        }
        return accepted;
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
        return changed == null ? data : new Data(changed, data.mode(), data.goal(), data.quantity());
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
                        .toList());
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
        if (Files.size(path) > 262144) {
            throw new IOException("Oversized memory file");
        }
        JsonObject root = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8))
                .getAsJsonObject();
        if (root.get("version").getAsInt() != 1) {
            throw new IOException("Unsupported memory schema");
        }
        Data data = gson.fromJson(root.get("data"), Data.class);
        if (data == null
                || data.landmarks().size() > MAX_LANDMARKS
                || !Set.of("quiet", "assist").contains(data.mode())
                || data.goal() == null
                || data.goal().length() > 64
                || data.quantity() < 1
                || data.quantity() > 64) {
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
        return data;
    }

    // 先写临时文件再原子替换，崩溃时不会留下半个文件。
    private void write(UUID player, Data data) throws IOException {
        Files.createDirectories(folder);
        Path target = folder.resolve(player + ".json");
        Path temp = Files.createTempFile(folder, "memory-", ".tmp");
        JsonObject root = new JsonObject();
        root.addProperty("version", 1);
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
