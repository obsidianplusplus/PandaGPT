package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.ai.AiRequest;
import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.LocalizedIOException;
import cn.panda.pandagpt.perception.EntityObservation;
import cn.panda.pandagpt.perception.Observation;
import cn.panda.pandagpt.perception.PerceptionController;
import cn.panda.pandagpt.perception.PerceptionSettings;
import cn.panda.pandagpt.perception.SceneObjects;
import cn.panda.pandagpt.perception.SpatialSurvey;
import cn.panda.pandagpt.performance.LoadGovernor;
import cn.panda.pandagpt.performance.TickBudget;
import cn.panda.pandagpt.vision.texture.TextureLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * 视觉采样调度：主线程按共享 tick 预算逐条推进射线，渲染线程只处理复制后的数据并写文件。
 *
 * <p>线程：除渲染任务外，全部方法在服务器主线程调用。
 */
public final class VisionController implements AutoCloseable {
    // 环境观察使用的低分辨率与距离。
    private static final int AMBIENT_WIDTH = 32;
    private static final int AMBIENT_HEIGHT = 18;
    private static final int AMBIENT_RANGE = 24;
    // 单条射线每次最多推进的格子数，避免一条长射线独占预算。
    private static final int CELLS_PER_SLICE = 32;
    private static final int MAX_MARKS = 64;

    /** 一次进行中的采样任务。 */
    private static final class Job {
        final UUID player;
        final UUID id = UUID.randomUUID();
        final World world;
        final UUID worldId;
        final Camera camera;
        final Camera.Projection projection;
        final BlockRaycaster raycaster;
        final int range;
        final long startedAt;
        final long startedTick;
        // 环境观察为 null；Agent 的 look 请求在完成或失败时结束这个 future。
        final CompletableFuture<Capture> result;
        final boolean debug;
        final boolean ambient;
        final SpatialSurvey survey;
        final List<RaySample> samples = new ArrayList<>();
        // 已命中的方块坐标；只有这些坐标变化才会使已采样数据过期。
        final Set<Long> hits = new HashSet<>();
        BlockRaycaster.Trace trace;
        List<EntityObservation> entities = List.of();
        boolean entitiesCaptured;
        int particles;
        volatile boolean cancelled;
        Future<?> rendering;

        Job(
                Player player,
                Camera camera,
                int range,
                long tick,
                long now,
                boolean debug,
                boolean ambient,
                PerceptionSettings perception) {
            this.player = player.getUniqueId();
            this.world = player.getWorld();
            this.worldId = world.getUID();
            this.camera = camera;
            this.projection = camera.projection();
            this.range = range;
            this.startedTick = tick;
            this.startedAt = now;
            this.result = ambient ? null : new CompletableFuture<>();
            this.debug = debug;
            this.ambient = ambient;
            raycaster = new BlockRaycaster(world, player.getSentChunkKeys());
            var feet = player.getLocation();
            survey = perception == null || !perception.enabled()
                    ? null
                    : new SpatialSurvey(
                            world,
                            player.getSentChunkKeys(),
                            new Camera.Vec3(feet.getX(), feet.getY(), feet.getZ()),
                            feet.getYaw());
        }
    }

    /** 可复用的射线切片许可，避免每推进一步就分配一个 lambda。 */
    private static final class SlicePermit implements BooleanSupplier {
        private TickBudget budget;
        private int remaining;

        SlicePermit reset(TickBudget budget) {
            this.budget = budget;
            remaining = CELLS_PER_SLICE;
            return this;
        }

        @Override
        public boolean getAsBoolean() {
            return remaining-- > 0 && budget.cell();
        }
    }

    private record Mark(
            UUID player, SceneObjects.Surface surface, Camera.Vec3 point, Observation observation, long expires) {}

    private final ChatGPTIntegration plugin;
    private final VisionSettings settings;
    private final Path folder;
    private final Clock clock;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Long> lastStarted = new HashMap<>();
    private final ThreadPoolExecutor renderer;
    private final TextureLibrary textures;
    private final boolean ownsTextures;
    private final List<Path> packs;
    // 自动下载完成后由主线程刷新。
    private volatile boolean assetsPresent;
    private final LoadGovernor governor = new LoadGovernor();
    private final int cellLimit;
    private final SlicePermit slice = new SlicePermit();
    private final ArrayDeque<Mark> marks = new ArrayDeque<>();
    private volatile boolean closed;
    private long tick;
    private int roundRobin;
    private long blockReads;
    private long cacheHits;
    private PerceptionController perception;
    private PerceptionSettings perceptionSettings;

    public VisionController(ChatGPTIntegration plugin, VisionSettings settings, Path dataFolder) {
        this(plugin, settings, dataFolder, null, Clock.systemUTC());
    }

    /**
     * @param textures 跨重载共享的纹理缓存；为 null 时创建并独占一个新的缓存
     */
    public VisionController(
            ChatGPTIntegration plugin, VisionSettings settings, Path dataFolder, TextureLibrary textures, Clock clock) {
        this.plugin = plugin;
        this.settings = settings;
        this.clock = clock;
        cellLimit = settings.cellsPerTick();
        folder = dataFolder.resolve("captures");
        packs = texturePaths(settings, dataFolder);
        assetsPresent = packs.stream().allMatch(Files::isReadable);
        ownsTextures = textures == null;
        this.textures = textures == null ? new TextureLibrary(packs, settings.textureCacheMiB()) : textures;
        renderer =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.SECONDS,
                        new ArrayBlockingQueue<>(settings.maxCaptures() + 1),
                        Thread.ofPlatform().daemon().name("pandagpt-vision-", 0).factory(),
                        new ThreadPoolExecutor.AbortPolicy()) {
                    @Override
                    protected void terminated() {
                        if (ownsTextures) {
                            VisionController.this.textures.close();
                        }
                    }
                };
        preload();
    }

    /** 资源包下载完成后重新检查并预加载。线程：主线程。 */
    public void refreshAssets() {
        boolean present = packs.stream().allMatch(Files::isReadable);
        if (present && !assetsPresent) {
            assetsPresent = true;
            preload();
        }
    }

    private void preload() {
        if (settings.enabled() && settings.textured() && assetsPresent && !renderer.isShutdown()) {
            renderer.execute(() -> {
                try {
                    this.textures.preload();
                } catch (IOException e) {
                    plugin.getLogger()
                            .warning(Lang.console(
                                    "log.texture_preload_failed", e.getClass().getSimpleName()));
                }
            });
        }
    }

    /** 把配置中的资源包路径解析为绝对路径；相对路径以插件数据目录为基准。 */
    public static List<Path> texturePaths(VisionSettings settings, Path dataFolder) {
        return settings.texturePacks().stream()
                .map(Path::of)
                .map(path -> path.isAbsolute()
                        ? path.normalize()
                        : dataFolder.resolve(path).normalize())
                .toList();
    }

    /**
     * 一次完成的视觉采样。
     *
     * @param saved 图片是否已写入 captures/ 目录
     */
    public record Capture(String summary, AiRequest.Image image, Observation observation, boolean saved) {}

    /**
     * Agent 的 look 工具：重建玩家当前第一人称视角。
     *
     * @param showHits 用私有粒子显示射线命中点（需要 pandagpt.vision.debug）
     * @return 采样完成后结束；视角变化、超时或被拒绝时以带原因的异常结束
     */
    public CompletableFuture<Capture> look(Player player, boolean showHits) {
        try {
            return start(player, showHits, false).result;
        } catch (LocalizedIOException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    public void attach(PerceptionController controller, PerceptionSettings settings) {
        perception = controller;
        perceptionSettings = settings;
    }

    /** 安排一次安静的环境观察：低分辨率、不读取纹理、不写文件、不调用模型。 */
    public boolean observe(Player player) {
        try {
            start(player, false, true);
            return true;
        } catch (LocalizedIOException e) {
            return false;
        }
    }

    private Job start(Player player, boolean debug, boolean ambient) throws LocalizedIOException {
        if (closed || !player.isOnline()) {
            throw new LocalizedIOException("vision.cancelled");
        }
        if (!settings.enabled()) {
            throw new LocalizedIOException("vision.disabled");
        }
        if (!ambient && settings.textured() && !assetsPresent) {
            throw new LocalizedIOException("vision.assets_missing");
        }
        if (!player.hasPermission("pandagpt.use") || !player.hasPermission("pandagpt.vision")) {
            throw new LocalizedIOException("vision.no_permission");
        }
        if (debug && !player.hasPermission("pandagpt.vision.debug")) {
            throw new LocalizedIOException("vision.no_debug_permission");
        }
        Job existing = jobs.get(player.getUniqueId());
        if (!ambient && existing != null && existing.ambient) {
            cancel(player.getUniqueId());
        }
        if (!ambient && jobs.size() >= settings.maxCaptures()) {
            // 显式请求可以回收其他玩家占用的环境观察名额。
            jobs.values().stream().filter(j -> j.ambient).findFirst().ifPresent(j -> cancel(j.player));
        }
        if (jobs.containsKey(player.getUniqueId()) || jobs.size() >= settings.maxCaptures()) {
            throw new LocalizedIOException("vision.busy");
        }
        long now = clock.millis();
        long previous = lastStarted.getOrDefault(player.getUniqueId(), 0L);
        if (!ambient && now - previous < settings.cooldownMillis()) {
            throw new LocalizedIOException("vision.cooldown");
        }
        var eye = player.getEyeLocation();
        Camera camera = new Camera(
                new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()),
                eye.getYaw(),
                eye.getPitch(),
                ambient ? AMBIENT_WIDTH : settings.width(),
                ambient ? AMBIENT_HEIGHT : settings.height(),
                settings.verticalFov());
        int viewChunks = Math.max(1, Math.min(player.getClientViewDistance(), player.getSendViewDistance()));
        int range = Math.min(ambient ? AMBIENT_RANGE : settings.maxDistance(), viewChunks * 16);
        Job job = new Job(player, camera, range, tick, now, debug, ambient, perceptionSettings);
        jobs.put(job.player, job);
        if (!ambient) {
            lastStarted.put(job.player, now);
        }
        return job;
    }

    /** 每秒按服务器 MSPT 调整一次负载状态，返回本 tick 的共享预算。 */
    public TickBudget newBudget() {
        if (tick % 20 == 0) {
            governor.observe(plugin.getServer().getAverageTickTime());
        }
        return governor.budget(settings.nanosPerTick(), cellLimit);
    }

    public void finishTick(TickBudget budget) {
        governor.record(budget);
    }

    public void tick() {
        TickBudget budget = newBudget();
        tick(budget);
        finishTick(budget);
    }

    public void tick(TickBudget budget) {
        if (closed) {
            return;
        }
        tick++;
        if (tick % 200 == 0) {
            long now = clock.millis();
            lastStarted.values().removeIf(time -> now - time >= settings.cooldownMillis());
        }
        // 空闲时不分配任何临时集合。
        if (jobs.isEmpty() && marks.isEmpty()) {
            return;
        }
        int used = 0;
        if (!jobs.isEmpty()) {
            used = sample(budget);
        }
        drawMarks(budget, used);
    }

    // 轮询推进各采样任务；配额与时间预算全局共享，不随玩家数量成倍增加。
    private int sample(TickBudget budget) {
        List<Job> active = new ArrayList<>(jobs.size());
        Map<UUID, Player> players = new HashMap<>();
        for (Job job : List.copyOf(jobs.values())) {
            Player player = plugin.getServer().getPlayer(job.player);
            if (player == null
                    || !player.isOnline()
                    || !player.hasPermission("pandagpt.use")
                    || !player.hasPermission("pandagpt.vision")) {
                cancel(job.player);
                continue;
            }
            var eye = player.getEyeLocation();
            if (!player.getWorld().getUID().equals(job.worldId)
                    || !job.camera.matches(
                            new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()),
                            eye.getYaw(),
                            eye.getPitch(),
                            settings)) {
                abort(job, "vision.moved");
                continue;
            }
            if (tick - job.startedTick > settings.timeoutTicks()) {
                abort(job, "vision.timeout");
                continue;
            }
            if (job.rendering == null && (!job.ambient || budget.background())) {
                job.raycaster.beginTick(tick);
                active.add(job);
                players.put(job.player, player);
            }
        }
        if (active.isEmpty()) {
            return 0;
        }
        Collections.rotate(active, -(roundRobin++ % active.size()));
        int stride = settings.maxParticles() == 0
                ? Integer.MAX_VALUE
                : Math.max(1, settings.width() * settings.height() / settings.maxParticles());
        int used = 0;
        while (!active.isEmpty() && used < settings.raysPerTick() && budget.available()) {
            for (var iterator = active.iterator(); iterator.hasNext(); ) {
                Job job = iterator.next();
                if (used >= settings.raysPerTick() || !budget.available()) {
                    break;
                }
                Player player = players.get(job.player);
                try {
                    int index = job.samples.size();
                    if (index == job.camera.width() * job.camera.height()) {
                        used += finishSampling(job, player, budget, iterator);
                        continue;
                    }
                    if (job.trace == null) {
                        int x = index % job.camera.width();
                        int y = index / job.camera.width();
                        job.trace = job.raycaster.begin(job.camera, job.projection.ray(x, y), job.range);
                    }
                    long readsBefore = job.raycaster.reads();
                    long hitsBefore = job.raycaster.cacheHits();
                    RaySample sample = job.trace.advance(slice.reset(budget));
                    blockReads += job.raycaster.reads() - readsBefore;
                    cacheHits += job.raycaster.cacheHits() - hitsBefore;
                    if (sample == null) {
                        continue;
                    }
                    used++;
                    job.trace = null;
                    job.samples.add(sample);
                    if (sample.kind() == RaySample.Kind.HIT) {
                        job.hits.add(pack(sample.blockX(), sample.blockY(), sample.blockZ()));
                        if (job.debug
                                && index % stride == 0
                                && job.particles < settings.maxParticles()
                                && player.hasPermission("pandagpt.vision.debug")) {
                            ParticleFeedback.show(player, sample);
                            job.particles++;
                        }
                    }
                } catch (RuntimeException e) {
                    abort(job, "vision.sampling_failed");
                    iterator.remove();
                    plugin.getLogger()
                            .warning(Lang.console(
                                    "log.vision_sampling_error", e.getClass().getSimpleName()));
                }
            }
        }
        return used;
    }

    // 射线全部完成后依次执行局部空间调查、实体采集，然后交给渲染线程。
    private int finishSampling(Job job, Player player, TickBudget budget, java.util.Iterator<Job> iterator) {
        int used = 0;
        if (job.survey != null && !job.survey.done() && budget.cell()) {
            job.survey.step();
            used++;
        }
        if ((job.survey != null && !job.survey.done()) || !budget.available()) {
            return used;
        }
        if (!job.entitiesCaptured) {
            // 原生实体查询无法中途打断；高负载时环境观察直接省略实体。
            job.entities = perceptionSettings == null || !perceptionSettings.enabled() || !budget.background()
                    ? List.of()
                    : EntityObservation.capture(player, perceptionSettings, clock.millis(), budget::available);
            job.entitiesCaptured = true;
            return used + 1;
        }
        render(job);
        iterator.remove();
        return used;
    }

    // 逐点重新射线确认遮挡后显示粒子标记，与采样共享预算。
    private void drawMarks(TickBudget budget, int used) {
        while (!marks.isEmpty() && used < settings.raysPerTick() && budget.available()) {
            Mark mark = marks.removeFirst();
            used++;
            Player player = plugin.getServer().getPlayer(mark.player());
            if (player == null
                    || !player.isOnline()
                    || !player.hasPermission("pandagpt.vision")
                    || !player.hasPermission("pandagpt.vision.debug")
                    || mark.expires() < clock.millis()
                    || !player.getWorld().getUID().equals(mark.observation().world())) {
                continue;
            }
            var eye = player.getEyeLocation();
            Camera current = new Camera(
                    new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()),
                    eye.getYaw(),
                    eye.getPitch(),
                    1,
                    1,
                    settings.verticalFov());
            Camera.Vec3 delta = mark.point().add(current.origin().scale(-1));
            if (delta.length() < .01 || delta.length() > settings.maxDistance()) {
                continue;
            }
            var raycaster = new BlockRaycaster(player.getWorld(), player.getSentChunkKeys());
            int range = Math.min(settings.maxDistance(), (int) Math.ceil(delta.length() + 1));
            RaySample hit = raycaster.begin(current, delta.normalize(), range).advance(budget::cell);
            if (hit != null
                    && hit.kind() == RaySample.Kind.HIT
                    && hit.material().equals(mark.surface().material())
                    && hit.hitPosition().distance(mark.point()) < .12) {
                ParticleFeedback.show(player, hit);
            }
        }
    }

    /** 为选中的对象排队最多 12 个轮廓点的粒子标记。 */
    public void mark(Player player, SceneObjects.Surface surface, Observation observation) {
        long expires = clock.millis() + 2000;
        for (var point : surface.visiblePoints().stream()
                .limit(Math.min(12, settings.maxParticles()))
                .toList()) {
            if (marks.size() >= MAX_MARKS) {
                break;
            }
            marks.add(new Mark(player.getUniqueId(), surface, point, observation, expires));
        }
    }

    /**
     * 方块变化：只有已命中的方块或已复制的调查格子发生变化才取消采样。
     *
     * <p>尚未采样的位置会在之后读取到新状态，无需取消；射线穿过的空气格中新出现的方块不在检测范围内，
     * 这类变化由完成时的视角核对与观察时间戳兜底。
     */
    public void changed(UUID world, SceneObjects.Pos pos) {
        long key = pack(pos.x(), pos.y(), pos.z());
        for (Job job : List.copyOf(jobs.values())) {
            if (!job.worldId.equals(world)) {
                continue;
            }
            if (job.hits.contains(key) || (job.survey != null && job.survey.contains(pos))) {
                abort(job, "vision.region_changed");
            }
        }
        marks.removeIf(m ->
                m.observation().world().equals(world) && m.surface().blocks().contains(pos));
    }

    // 把方块坐标压缩为一个 long：x、z 各 26 位，y 12 位。
    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    private void render(Job job) {
        // 交给渲染线程前释放持有活动方块引用的缓存。
        job.raycaster.beginTick(Long.MAX_VALUE);
        SceneRenderer.Frame frame = new SceneRenderer.Frame(
                job.player,
                job.id,
                job.world.getName(),
                job.camera,
                job.range,
                job.startedAt,
                clock.millis(),
                job.samples);
        job.rendering = renderer.submit(() -> {
            try {
                Observation observation = perception == null || job.survey == null
                        ? null
                        : Observation.create(
                                job.id,
                                job.worldId,
                                frame.completedAt(),
                                frame.camera(),
                                job.range,
                                frame.pixels(),
                                job.survey.report(),
                                job.entities);
                SceneRenderer.Rendered image = null;
                if (!job.ambient) {
                    image = settings.textured()
                            ? textures.use(t -> SceneRenderer.render(frame, t, observation))
                            : SceneRenderer.render(frame, null, observation);
                }
                if (job.cancelled || closed) {
                    return;
                }
                if (!job.ambient && settings.saveImages()) {
                    save(job, image);
                }
                if (job.cancelled || closed) {
                    return;
                }
                SceneRenderer.Rendered result = image;
                plugin.getServer().getScheduler().runTask(plugin, () -> complete(job, result, observation));
            } catch (IOException | RuntimeException e) {
                if (job.cancelled || closed) {
                    return;
                }
                plugin.getLogger()
                        .warning(Lang.console(
                                "log.vision_render_failed", e.getClass().getSimpleName(), e.getMessage()));
                try {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (jobs.get(job.player) != job) {
                            return;
                        }
                        abort(job, "vision.render_failed");
                    });
                } catch (RuntimeException ignored) {
                    // 停用插件与回调调度发生竞争，忽略即可。
                }
            }
        });
    }

    // 元数据最后写入；capture_id 用于区分前后两次采样。
    private void save(Job job, SceneRenderer.Rendered image) throws IOException {
        Path directory = folder.resolve(job.player.toString());
        Files.createDirectories(directory);
        write(job, directory.resolve("color.png"), image.colorPng());
        write(job, directory.resolve("depth.png"), image.depthPng());
        write(job, directory.resolve("labels.png"), image.labelsPng());
        write(job, directory.resolve("metadata.json"), image.metadata().getBytes(StandardCharsets.UTF_8));
    }

    private static void write(Job job, Path target, byte[] data) throws IOException {
        if (job.cancelled || Thread.currentThread().isInterrupted()) {
            throw new IOException("Capture cancelled");
        }
        Path temporary = Files.createTempFile(target.getParent(), "frame-", ".tmp");
        try {
            Files.write(temporary, data);
            if (job.cancelled || Thread.currentThread().isInterrupted()) {
                throw new IOException("Capture cancelled");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // 主线程回调：再次核对玩家、世界与视角，过期画面绝不发送给模型。
    private void complete(Job job, SceneRenderer.Rendered image, Observation observation) {
        if (closed || job.cancelled || jobs.get(job.player) != job) {
            return;
        }
        jobs.remove(job.player);
        Player player = plugin.getServer().getPlayer(job.player);
        if (player == null
                || !player.isOnline()
                || !player.hasPermission("pandagpt.use")
                || !player.hasPermission("pandagpt.vision")) {
            fail(job, "vision.cancelled");
            return;
        }
        var eye = player.getEyeLocation();
        if (!player.getWorld().getUID().equals(job.worldId)
                || !job.camera.matches(
                        new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()), eye.getYaw(), eye.getPitch(), settings)) {
            fail(job, "vision.view_changed");
            return;
        }
        if (perception != null && observation != null) {
            perception.accept(player, observation);
        }
        if (job.result != null) {
            job.result.complete(new Capture(image.summary(), image.image(), observation, settings.saveImages()));
        }
    }

    // 以带原因的异常结束 look 请求，再取消任务。
    private void abort(Job job, String key) {
        fail(job, key);
        cancel(job.player);
    }

    private static void fail(Job job, String key) {
        if (job.result != null) {
            job.result.completeExceptionally(new LocalizedIOException(key));
        }
    }

    public void cancel(UUID player) {
        marks.removeIf(m -> m.player().equals(player));
        Job job = jobs.remove(player);
        if (job != null) {
            fail(job, "vision.cancelled");
            job.cancelled = true;
            if (job.rendering != null) {
                job.rendering.cancel(true);
            }
        }
        renderer.purge();
    }

    public void clearAll() {
        marks.clear();
        for (UUID id : List.copyOf(jobs.keySet())) {
            cancel(id);
        }
    }

    public String status() {
        return "vision=" + settings.enabled() + ", captures=" + jobs.size() + ", size=" + settings.width() + "x"
                + settings.height() + ", " + governor.status() + ", block_reads=" + blockReads + ", cache_hits="
                + cacheHits + ", cell_limit=" + cellLimit + ", render_queue="
                + renderer.getQueue().size()
                + ", textures=" + settings.textured() + ", assets_present=" + assetsPresent;
    }

    @Override
    public void close() {
        closed = true;
        clearAll();
        renderer.shutdownNow();
    }
}
