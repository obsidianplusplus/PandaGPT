package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.service.AssistantService;
import cn.panda.pandagpt.vision.texture.*;
import cn.panda.pandagpt.perception.*;
import cn.panda.pandagpt.performance.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Main-thread sampling with a shared tick budget. Worker threads only render copied values and write files. */
public final class VisionController implements AutoCloseable {
    private static final class Job {
        final UUID player, id = UUID.randomUUID();
        final World world;
        final UUID worldId;
        final Camera camera;
        final Camera.Projection projection;
        BlockRaycaster.Trace trace;
        final BlockRaycaster raycaster;
        final int range;
        final long startedAt = System.currentTimeMillis(), startedTick;
        final String question;
        final boolean debug;
        final boolean ambient;
        final SpatialSurvey survey;
        List<EntityObservation> entities = List.of();
        boolean entitiesCaptured;
        final List<RaySample> samples = new ArrayList<>();
        int particles;
        volatile boolean cancelled;
        Future<?> rendering;
        Job(Player player, Camera camera, int range, long tick, String question, boolean debug, boolean ambient, PerceptionSettings perception) {
            this.player = player.getUniqueId(); this.world = player.getWorld(); this.worldId = world.getUID(); this.camera = camera; this.projection = camera.projection();
            this.range = range; this.startedTick = tick; this.question = question; this.debug = debug;
            this.ambient = ambient;
            raycaster = new BlockRaycaster(world, player.getSentChunkKeys());
            var feet = player.getLocation();
            survey = perception == null || !perception.enabled() ? null : new SpatialSurvey(world, player.getSentChunkKeys(),
                    new Camera.Vec3(feet.getX(), feet.getY(), feet.getZ()), feet.getYaw());

        }
    }
    private final ChatGPTIntegration plugin;
    private final VisionSettings settings;
    private final AssistantService assistant;
    private final Path folder;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Long> lastStarted = new HashMap<>();
    private final ThreadPoolExecutor renderer;
    private final List<Path> texturePaths;
    private final boolean assetsPresent;
    private volatile BlockTextures textures;
    private volatile boolean closed;
    private long tick;
    private int roundRobin;
    private long blockReads, cacheHits;
    private final LoadGovernor governor = new LoadGovernor();
    private final int cellLimit;
    private PerceptionController perception;
    private PerceptionSettings perceptionSettings;
    private record Mark(UUID player, SceneObjects.Surface surface, Camera.Vec3 point, Observation observation, long expires) {}
    private final ArrayDeque<Mark> marks = new ArrayDeque<>();

    public VisionController(ChatGPTIntegration plugin, VisionSettings settings, AssistantService assistant, Path dataFolder) {
        this.plugin = plugin; this.settings = settings;
        cellLimit = settings.cellsPerTick(); this.assistant = assistant; folder = dataFolder.resolve("captures");
        texturePaths = settings.texturePacks().stream().map(Path::of)
                .map(path -> path.isAbsolute() ? path.normalize() : dataFolder.resolve(path).normalize()).toList();
        assetsPresent = texturePaths.stream().allMatch(Files::isReadable);
        renderer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(settings.maxCaptures()),
                Thread.ofPlatform().daemon().name("pandagpt-vision-", 0).factory(), new ThreadPoolExecutor.AbortPolicy()) {
            @Override protected void terminated() {
                if (VisionController.this.textures != null) VisionController.this.textures.close();
            }
        };
    }
    public void capture(Player player, String question, boolean debug) {
        start(player, question, debug, false);
    }
    public void attach(PerceptionController controller, PerceptionSettings settings) { perception = controller; perceptionSettings = settings; }
    public boolean observe(Player player) { return start(player, null, false, true); }
    private boolean start(Player player, String question, boolean debug, boolean ambient) {
        if (closed || !player.isOnline()) return false;
        if (!settings.enabled()) { if (!ambient) tell(player, "视觉重建未开启。"); return false; }
        if (!ambient && settings.textured() && !assetsPresent) {
            if (!ambient) tell(player, "缺少纹理资源。请管理员运行 tools/download_vanilla_assets.py，或配置 textures.packs 后重载。"); return false;
        }
        if (!player.hasPermission("pandagpt.use") || !player.hasPermission("pandagpt.vision")) {
            if (!ambient) tell(player, "你没有使用视觉重建的权限。"); return false;
        }
        if (debug && !player.hasPermission("pandagpt.vision.debug")) { if (!ambient) tell(player, "你没有显示采样粒子的权限。"); return false; }
        if (question != null && (question.isBlank() || question.length() > plugin.settings().maxPromptChars())) {
            if (!ambient) tell(player, "请输入有效的视觉问题，长度不能超过提问上限。"); return false;
        }
        if (question != null && !assistant.configured()) { if (!ambient) tell(player, "尚未配置模型密钥；可先使用 vision snapshot。"); return false; }
        if (assistant.busy(player.getUniqueId())) { if (!ambient) tell(player, "上一条 AI 问题仍在处理。"); return false; }
        Job existing = jobs.get(player.getUniqueId());
        if (!ambient && existing != null && existing.ambient) cancel(player.getUniqueId());
        if (!ambient && jobs.size() >= settings.maxCaptures()) {
            // A requested capture may reclaim an ambient slot belonging to another player.
            Job background = jobs.values().stream().filter(j -> j.ambient).findFirst().orElse(null);
            if (background != null) cancel(background.player);
        }
        if (jobs.containsKey(player.getUniqueId()) || jobs.size() >= settings.maxCaptures()) {
            if (!ambient) tell(player, "视觉采样忙碌，请稍后再试。"); return false;
        }
        long now = System.currentTimeMillis(), previous = lastStarted.getOrDefault(player.getUniqueId(), 0L);
        if (!ambient && now - previous < settings.cooldownMillis()) { if (!ambient) tell(player, "视觉采样正在冷却，请稍后再试。"); return false; }
        var eye = player.getEyeLocation();
        Camera camera = new Camera(new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()), eye.getYaw(), eye.getPitch(),
                ambient ? 32 : settings.width(), ambient ? 18 : settings.height(), settings.verticalFov());
        int viewChunks = Math.max(1, Math.min(player.getClientViewDistance(), player.getSendViewDistance()));
        int range = Math.min(ambient ? 24 : settings.maxDistance(), viewChunks * 16);
        Job job = new Job(player, camera, range, tick, question, debug, ambient, perceptionSettings);
        jobs.put(job.player, job); if (!ambient) lastStarted.put(job.player, now);
        if (!ambient) tell(player, "正在重建第一人称场景，请保持位置和视线稳定。粒子仅用于显示射线命中点。");
        return true;
    }

    public TickBudget newBudget() {
        if (tick % 20 == 0) governor.observe(plugin.getServer().getAverageTickTime());
        return governor.budget(settings.nanosPerTick(), cellLimit);
    }
    public void finishTick(TickBudget budget) { governor.record(budget); }
    public void tick() { TickBudget budget = newBudget(); tick(budget); finishTick(budget); }
    public void tick(TickBudget budget) {
        if (closed) return;
        tick++;
        if (tick % 200 == 0) lastStarted.values().removeIf(time -> System.currentTimeMillis() - time >= settings.cooldownMillis());
        List<Job> active = new ArrayList<>();
        Map<UUID, Player> players = new HashMap<>();
        for (Job job : List.copyOf(jobs.values())) {
            Player player = plugin.getServer().getPlayer(job.player);
            if (player == null || !player.isOnline() || !player.hasPermission("pandagpt.use") || !player.hasPermission("pandagpt.vision")) {
                cancel(job.player); continue;
            }
            var eye = player.getEyeLocation();
            if (!player.getWorld().getUID().equals(job.world.getUID())
                    || !job.camera.matches(new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()), eye.getYaw(), eye.getPitch(), settings)) {
                cancel(job.player); if (!job.ambient) tell(player, "位置或视线变化较大，本次重建已取消，请重新采样。"); continue;
            }
            if (tick - job.startedTick > settings.timeoutTicks()) {
                cancel(job.player); if (!job.ambient) tell(player, "视觉重建超过时间预算，请降低分辨率或距离。"); continue;
            }
            if (job.rendering == null && (!job.ambient || budget.background())) { job.raycaster.beginTick(tick); active.add(job); players.put(job.player, player); }
        }
        if (!active.isEmpty()) Collections.rotate(active, -(roundRobin++ % active.size()));
        int used = 0;
        // Round-robin across captures; quota/time budget is global, not multiplied by player count.
        while (!active.isEmpty() && used < settings.raysPerTick() && budget.available()) {
            for (var iterator = active.iterator(); iterator.hasNext();) {
                Job job = iterator.next();
                if (used >= settings.raysPerTick() || !budget.available()) break;
                int index = job.samples.size(), x = index % job.camera.width(), y = index / job.camera.width();
                try {
                    if (index == job.camera.width() * job.camera.height()) {
                        if (job.survey != null && !job.survey.done() && budget.cell()) { job.survey.step(); used++; }
                        if ((job.survey == null || job.survey.done()) && budget.available()) {
                            if (!job.entitiesCaptured) {
                                // Native entity queries cannot be preempted. Ambient frames omit them under load.
                                job.entities = perceptionSettings == null || !perceptionSettings.enabled() || !budget.background()
                                        ? List.of() : EntityObservation.capture(players.get(job.player), perceptionSettings, System.currentTimeMillis(), budget::available);
                                job.entitiesCaptured = true; used++; continue;
                            }
                            render(job); iterator.remove();
                        }
                        continue;
                    }
                    if (job.trace == null) job.trace = job.raycaster.begin(job.camera, job.projection.ray(x, y), job.range);
                    int[] slice = {0};
                    long readsBefore = job.raycaster.reads(), hitsBefore = job.raycaster.cacheHits();
                    RaySample sample = job.trace.advance(() -> slice[0]++ < 32 && budget.cell());
                    blockReads += job.raycaster.reads() - readsBefore; cacheHits += job.raycaster.cacheHits() - hitsBefore;
                    if (sample == null) continue;
                    used++; job.trace = null; job.samples.add(sample);
                    int stride = settings.maxParticles() == 0 ? Integer.MAX_VALUE
                            : Math.max(1, settings.width() * settings.height() / settings.maxParticles());
                    if (job.debug && index % stride == 0 && job.particles < settings.maxParticles()
                            && sample.kind() == RaySample.Kind.HIT
                            && players.get(job.player).hasPermission("pandagpt.vision.debug")) {
                        ParticleFeedback.show(players.get(job.player), sample); job.particles++;
                    }
                } catch (RuntimeException e) {
                    cancel(job.player); iterator.remove();
                    if (!job.ambient) tell(players.get(job.player), "射线采样失败，本次重建已取消。");
                    plugin.getLogger().warning("Vision sampling error: " + e.getClass().getSimpleName());
                }
            }
        }
        while (!marks.isEmpty() && used < settings.raysPerTick() && budget.available()) {
            Mark mark = marks.removeFirst(); used++;
            Player player = plugin.getServer().getPlayer(mark.player());
            if (player == null || !player.isOnline() || !player.hasPermission("pandagpt.vision")
                    || !player.hasPermission("pandagpt.vision.debug") || mark.expires() < System.currentTimeMillis()
                    || !player.getWorld().getUID().equals(mark.observation().world())) continue;
            var eye = player.getEyeLocation(); Camera current = new Camera(new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()), eye.getYaw(), eye.getPitch(), 1, 1, settings.verticalFov());
            Camera.Vec3 delta = mark.point().add(current.origin().scale(-1));
            if (delta.length() < .01 || delta.length() > settings.maxDistance()) continue;
            var raycaster = new BlockRaycaster(player.getWorld(), player.getSentChunkKeys());
            RaySample hit = raycaster.begin(current, delta.normalize(), Math.min(settings.maxDistance(), (int) Math.ceil(delta.length() + 1))).advance(budget::cell);
            if (hit != null && hit.kind() == RaySample.Kind.HIT && hit.material().equals(mark.surface().material()) && hit.hitPosition().distance(mark.point()) < .12)
                ParticleFeedback.show(player, hit);
        }
    }

    public void mark(Player player, SceneObjects.Surface surface, Observation observation) {
        for (var point : surface.visiblePoints().stream().limit(Math.min(12, settings.maxParticles())).toList()) {
            if (marks.size() >= 64) break;
            marks.add(new Mark(player.getUniqueId(), surface, point, observation, System.currentTimeMillis() + 2000));
        }
    }
    public void changed(UUID world, SceneObjects.Pos pos) {
        for (Job job : List.copyOf(jobs.values())) if (job.worldId.equals(world)
                && (job.camera.origin().distance(new Camera.Vec3(pos.x() + .5, pos.y() + .5, pos.z() + .5)) <= job.range + 2
                || job.survey != null && job.survey.contains(pos))) {
            cancel(job.player);
            Player p = plugin.getServer().getPlayer(job.player);
            if (!job.ambient && p != null) tell(p, "已采样区域发生变化，本次重建已取消，请重新观察。");
        }
        marks.removeIf(m -> m.observation().world().equals(world) && m.surface().blocks().contains(pos));
    }

    private void render(Job job) {
        job.raycaster.beginTick(Long.MAX_VALUE); // Release live-block cache before the copied-data worker takes ownership.
        SceneRenderer.Frame frame = new SceneRenderer.Frame(job.player, job.id, job.world.getName(), job.camera,
                job.range, job.startedAt, System.currentTimeMillis(), job.samples);
        job.rendering = renderer.submit(() -> {
            try {
                if (!job.ambient && settings.textured() && textures == null)
                    textures = new BlockTextures(new ResourcePack(texturePaths, settings.textureCacheMiB()));
                Observation observation = perception == null || job.survey == null ? null : Observation.create(job.id, job.worldId,
                        frame.completedAt(), frame.camera(), job.range, frame.pixels(), job.survey.report(), job.entities);
                SceneRenderer.Rendered image = job.ambient ? null : SceneRenderer.render(frame, textures, observation);
                if (job.cancelled || closed) return;
                if (!job.ambient && settings.saveImages()) save(job, image);
                if (job.cancelled || closed) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> complete(job, image, observation));
            } catch (IOException | RuntimeException e) {
                if (job.cancelled || closed) return;
                plugin.getLogger().warning("Vision render/save failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                try {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (jobs.get(job.player) != job) return;
                        cancel(job.player);
                        Player player = plugin.getServer().getPlayer(job.player);
                        if (player != null && !job.ambient) tell(player, "图片生成或保存失败，请联系管理员。");
                    });
                } catch (RuntimeException ignored) { /* Disable raced with callback. */ }
            }
        });
    }
    private void save(Job job, SceneRenderer.Rendered image) throws IOException {
        Path directory = folder.resolve(job.player.toString());
        Files.createDirectories(directory);
        write(job, directory.resolve("color.png"), image.colorPng());
        write(job, directory.resolve("depth.png"), image.depthPng());
        write(job, directory.resolve("labels.png"), image.labelsPng());
        // Metadata is written last; capture_id distinguishes successive captures.
        write(job, directory.resolve("metadata.json"), image.metadata().getBytes(StandardCharsets.UTF_8));
    }
    private static void write(Job job, Path target, byte[] data) throws IOException {
        if (job.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Capture cancelled");
        Path temporary = Files.createTempFile(target.getParent(), "frame-", ".tmp");
        try {
            Files.write(temporary, data);
            if (job.cancelled || Thread.currentThread().isInterrupted()) throw new IOException("Capture cancelled");
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    private void complete(Job job, SceneRenderer.Rendered image, Observation observation) {
        if (closed || job.cancelled || jobs.get(job.player) != job) return;
        jobs.remove(job.player);
        Player player = plugin.getServer().getPlayer(job.player);
        if (player == null || !player.isOnline() || !player.hasPermission("pandagpt.use") || !player.hasPermission("pandagpt.vision")) return;
        var eye = player.getEyeLocation();
        if (!player.getWorld().getUID().equals(job.world.getUID())
                || !job.camera.matches(new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()), eye.getYaw(), eye.getPitch(), settings)) {
            if (!job.ambient) tell(player, "图片生成期间视角变化，旧画面不会发送给模型，请重新采样。"); return;
        }
        if (perception != null && observation != null) perception.accept(player, observation);
        if (job.ambient) return;
        tell(player, settings.saveImages()
                ? "重建完成：captures/" + job.player + "/ 下包含 color.png、depth.png、labels.png 与 metadata.json。"
                : "重建完成；文件保存已关闭。");
        if (job.question != null) assistant.ask(player, job.question, image.summary()
                + (observation == null ? "" : "\n" + observation.context()), image.image(),
                observation == null ? null : perception.visualQuery(player, observation));
    }
    public void cancel(UUID player) {
        marks.removeIf(m -> m.player().equals(player));
        Job job = jobs.remove(player);
        if (job != null) { job.cancelled = true; if (job.rendering != null) job.rendering.cancel(true); }
        renderer.purge();
    }
    public void clearAll() { marks.clear(); for (UUID id : List.copyOf(jobs.keySet())) cancel(id); }
    public String status() {
        return "vision=" + settings.enabled() + ", captures=" + jobs.size() + ", size=" + settings.width() + "x" + settings.height()
                + ", " + governor.status() + ", block_reads=" + blockReads + ", cache_hits=" + cacheHits + ", cell_limit=" + cellLimit + ", render_queue=" + renderer.getQueue().size()
                + ", textures=" + settings.textured() + ", assets_present=" + assetsPresent;
    }
    private static void tell(Player player, String text) {
        player.sendMessage(Component.text("[Panda Vision] " + text, NamedTextColor.AQUA));
    }
    @Override public void close() { closed = true; clearAll(); renderer.shutdownNow(); }
}
