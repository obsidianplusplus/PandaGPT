package cn.panda.pandagpt.perception;

import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** UUID-private, bounded durable landmarks/preferences. All file operations run on one ordered worker. */
public final class SpatialMemory implements AutoCloseable {
    public record Landmark(String name, UUID world, SceneObjects.Pos position, String state, String source,
                           long observedAt, long verifiedAt) {}
    public record Data(List<Landmark> landmarks, String mode, String goal, int quantity) {
        public Data { landmarks = List.copyOf(landmarks); }
        private static final Data EMPTY = new Data(List.of(), "assist", "", 1);
        public static Data empty() { return EMPTY; }
    }
    private final Path folder; private volatile int limit; private final Consumer<String> errors;
    private final Map<UUID, Data> cache = new ConcurrentHashMap<>(); private final Set<UUID> requested = ConcurrentHashMap.newKeySet();
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
    private final ThreadPoolExecutor io; private final Gson gson = new Gson();
    public SpatialMemory(Path folder, int limit, Consumer<String> errors) {
        this.folder = folder; this.limit = limit; this.errors = errors;
        io = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(limit * 2),
                Thread.ofPlatform().daemon().name("pandagpt-memory-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    public Data view(UUID player) { request(player); return mask(player, cache.getOrDefault(player, Data.empty())); }
    public boolean ready(UUID player) { return cache.containsKey(player) && !failed.contains(player); }
    public void setLimit(int limit) { this.limit = limit; }
    private synchronized boolean request(UUID player) {
        if (requested.contains(player)) return true;
        if (requested.size() >= limit) return false;
        requested.add(player);
        if (!submit(() -> {
            try { Data loaded = read(player); cache.put(player, loaded); index(player, loaded); }
            catch (Exception e) { failed.add(player); errors.accept("Memory load failed for " + player + ": " + e.getClass().getSimpleName()); }
        })) { requested.remove(player); return false; }
        return true;
    }
    public boolean edit(UUID player, UnaryOperator<Data> update) {
        return edit(player, update, () -> {});
    }
    private boolean edit(UUID player, UnaryOperator<Data> update, Runnable done) {
        if (!request(player)) return false;
        return submit(() -> {
            try {
                if (!ready(player)) return; // Never overwrite an unreadable or unparsed prior file.
                Data next = mask(player, update.apply(mask(player, cache.get(player))));
                write(player, next); cache.put(player, mask(player, next)); index(player, next);
                Set<Key> retained = new HashSet<>(); for (Landmark l : next.landmarks()) retained.add(new Key(player, l.world(), l.position()));
                invalidations.keySet().removeIf(k -> k.player().equals(player) && !retained.contains(k) && !pendingLandmarks.containsKey(k));
            }
            catch (Exception e) { errors.accept("Memory save failed for " + player + ": " + e.getClass().getSimpleName()); }
            finally { done.run(); }
        });
    }
    public boolean remember(UUID player, Landmark landmark) {
        if (landmark.name().isBlank() || landmark.name().length() > 64) return false;
        Key key = new Key(player, landmark.world(), landmark.position());
        synchronized (indexLock) { pendingLandmarks.merge(key, 1, Integer::sum); addOwner(key); }
        Runnable done = () -> { synchronized (indexLock) {
            pendingLandmarks.computeIfPresent(key, (k, count) -> count <= 1 ? null : count - 1);
            if (!pendingLandmarks.containsKey(key) && !indexed.getOrDefault(player, Set.of()).contains(key)) removeOwner(key);
        } };
        boolean accepted = edit(player, data -> {
            var list = new ArrayList<>(data.landmarks()); list.removeIf(l -> l.name().equals(landmark.name()) && l.world().equals(landmark.world()));
            list.add(landmark);
            while (list.size() > 32) {
                Landmark automatic = list.stream().filter(l -> !l.source().equals("explicit_player_mark")).findFirst().orElse(null);
                if (automatic != null) list.remove(automatic); else list.removeFirst();
            }
            return new Data(list, data.mode(), data.goal(), data.quantity());
        }, done);
        if (!accepted) done.run(); return accepted;
    }
    private void addOwner(Key key) { owners.computeIfAbsent(new Position(key.world(), key.position()), p -> new HashSet<>()).add(key); }
    private void removeOwner(Key key) {
        Position pos = new Position(key.world(), key.position()); Set<Key> keys = owners.get(pos);
        if (keys != null) { keys.remove(key); if (keys.isEmpty()) owners.remove(pos); }
    }
    private void index(UUID player, Data data) {
        synchronized (indexLock) {
            Set<Key> next = new HashSet<>(); for (Landmark l : data.landmarks()) next.add(new Key(player, l.world(), l.position()));
            for (Key key : indexed.getOrDefault(player, Set.of())) if (!next.contains(key) && !pendingLandmarks.containsKey(key)) removeOwner(key);
            for (Key key : next) addOwner(key); indexed.put(player, next);
        }
    }
    public void invalidate(UUID world, SceneObjects.Pos position) {
        Set<UUID> affected = new HashSet<>(); long now = System.currentTimeMillis();
        synchronized (indexLock) {
            // O(owners at this coordinate), independent of all other players and landmarks.
            for (Key key : owners.getOrDefault(new Position(world, position), Set.of())) {
                long previous = invalidations.getOrDefault(key, Long.MIN_VALUE);
                boolean fresh = cache.getOrDefault(key.player(), Data.empty()).landmarks().stream()
                        .anyMatch(l -> l.world().equals(world) && l.position().equals(position) && l.verifiedAt() > 0 && l.verifiedAt() > previous);
                if (!fresh && !pendingLandmarks.containsKey(key)) continue;
                invalidations.put(key, now); affected.add(key.player()); dirty.merge(key.player(), 1L, Long::sum);
            }
        }
        for (UUID player : affected) saveInvalidations(player);
    }
    private void saveInvalidations(UUID player) {
        if (!scheduled.add(player)) return; // One outstanding invalidation write per player, even during redstone storms.
        if (!submit(() -> {
            long revision = dirty.getOrDefault(player, 0L);
            try {
                if (ready(player)) {
                    Data next = mask(player, cache.get(player)); write(player, next); cache.put(player, mask(player, next));
                }
            } catch (Exception e) { errors.accept("Memory invalidation save failed: " + e.getClass().getSimpleName()); }
            finally {
                scheduled.remove(player);
                if (dirty.getOrDefault(player, 0L) != revision) saveInvalidations(player);
            }
        })) scheduled.remove(player);
    }
    private Data mask(UUID player, Data data) {
        List<Landmark> changed = null;
        for (int i = 0; i < data.landmarks().size(); i++) {
            Landmark l = data.landmarks().get(i);
            long invalidated = invalidations.getOrDefault(new Key(player, l.world(), l.position()), Long.MIN_VALUE);
            if (l.verifiedAt() > 0 && l.verifiedAt() <= invalidated) {
                if (changed == null) changed = new ArrayList<>(data.landmarks());
                changed.set(i, new Landmark(l.name(), l.world(), l.position(), l.state(), l.source(), l.observedAt(), 0));
            }
        }
        return changed == null ? data : new Data(changed, data.mode(), data.goal(), data.quantity());
    }
    public void release(UUID player) {
        submit(() -> { synchronized (indexLock) {
            for (Key key : indexed.getOrDefault(player, Set.of())) removeOwner(key);
            for (Key key : pendingLandmarks.keySet()) if (key.player().equals(player)) removeOwner(key);
            indexed.remove(player); dirty.remove(player);
        } cache.remove(player); failed.remove(player); requested.remove(player); invalidations.keySet().removeIf(k -> k.player().equals(player)); pendingLandmarks.keySet().removeIf(k -> k.player().equals(player)); });
    }
    public String context(UUID player, UUID world, long now) {
        Data data = view(player);
        if (!ready(player)) return "Spatial memory unavailable or loading; do not infer prior locations.";
        return "Remembered landmarks (historical, not current proof; verifiedAt=0 means invalidated; recheck before acting): "
                + gson.toJson(data.landmarks().stream().filter(l -> l.world().equals(world)).sorted(Comparator.comparingLong(Landmark::observedAt).reversed()).limit(12).toList());
    }
    private boolean submit(Runnable task) { try { io.execute(task); return true; } catch (RejectedExecutionException e) { errors.accept("Memory I/O queue is full or stopped"); return false; } }
    private Data read(UUID player) throws IOException {
        Path path = folder.resolve(player + ".json"); if (!Files.exists(path)) return Data.empty();
        if (Files.size(path) > 262144) throw new IOException("Oversized memory file");
        JsonObject root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        if (root.get("version").getAsInt() != 1) throw new IOException("Unsupported memory schema");
        Data data = gson.fromJson(root.get("data"), Data.class);
        if (data == null || data.landmarks().size() > 32 || !Set.of("quiet", "assist").contains(data.mode()) || data.goal() == null
                || data.goal().length() > 64 || data.quantity() < 1 || data.quantity() > 64) throw new IOException("Invalid memory data");
        for (Landmark l : data.landmarks()) if (l.name() == null || l.name().length() > 64 || l.world() == null || l.position() == null
                || l.state() == null || l.state().length() > 512 || l.source() == null || l.source().length() > 64) throw new IOException("Invalid landmark");
        return data;
    }
    private void write(UUID player, Data data) throws IOException {
        Files.createDirectories(folder); Path target = folder.resolve(player + ".json"); Path temp = Files.createTempFile(folder, "memory-", ".tmp");
        JsonObject root = new JsonObject(); root.addProperty("version", 1); root.add("data", gson.toJsonTree(data));
        try {
            Files.writeString(temp, gson.toJson(root), StandardCharsets.UTF_8);
            try { Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    /** Test/shutdown hook: submitted writes finish in order; no Bukkit access occurs on the I/O worker. */
    public boolean drain(long millis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
        FutureTask<Void> barrier = new FutureTask<>(() -> null);
        try { io.execute(barrier); }
        catch (RejectedExecutionException e) {
            if (io.isShutdown() || !io.getQueue().offer(barrier, millis, TimeUnit.MILLISECONDS)) return false;
        }
        try { barrier.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS); return true; }
        catch (ExecutionException | TimeoutException e) { return false; }
    }
    @Override public void close() {
        io.shutdown();
        try {
            if (!io.awaitTermination(2, TimeUnit.SECONDS)) errors.accept("Memory writes are still draining after the 2-second shutdown budget");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
