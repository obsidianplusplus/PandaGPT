package cn.panda.pandagpt.vision;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.service.AssistantService;
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
        final Camera camera;
        final BlockRaycaster raycaster;
        final int range;
        final long startedAt = System.currentTimeMillis(), startedTick;
        final String question;
        final boolean debug;
        final List<RaySample> samples = new ArrayList<>();
        int particles;
        volatile boolean cancelled;
        Future<?> rendering;
        Job(Player player, Camera camera, int range, long tick, String question, boolean debug) {
            this.player = player.getUniqueId(); this.world = player.getWorld(); this.camera = camera;
            this.range = range; this.startedTick = tick; this.question = question; this.debug = debug;
            raycaster = new BlockRaycaster(world, player.getSentChunkKeys());
        }
    }
    private final ChatGPTIntegration plugin;
    private final VisionSettings settings;
    private final AssistantService assistant;
    private final Path folder;
    private final Map<UUID, Job> jobs = new LinkedHashMap<>();
    private final Map<UUID, Long> lastStarted = new HashMap<>();
    private final ThreadPoolExecutor renderer;
    private volatile boolean closed;
    private long tick;

    public VisionController(ChatGPTIntegration plugin, VisionSettings settings, AssistantService assistant, Path dataFolder) {
        this.plugin = plugin; this.settings = settings; this.assistant = assistant; folder = dataFolder.resolve("captures");
        renderer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(settings.maxCaptures()),
                Thread.ofPlatform().daemon().name("pandagpt-vision-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    public void capture(Player player, String question, boolean debug) {
        if (closed || !player.isOnline()) return;
        if (!settings.enabled()) { tell(player, "视觉重建未开启。"); return; }
        if (!player.hasPermission("pandagpt.use") || !player.hasPermission("pandagpt.vision")) {
            tell(player, "你没有使用视觉重建的权限。"); return;
        }
        if (debug && !player.hasPermission("pandagpt.vision.debug")) { tell(player, "你没有显示采样粒子的权限。"); return; }
        if (question != null && (question.isBlank() || question.length() > plugin.settings().maxPromptChars())) {
            tell(player, "请输入有效的视觉问题，长度不能超过提问上限。"); return;
        }
        if (question != null && !assistant.configured()) { tell(player, "尚未配置模型密钥；可先使用 vision snapshot。"); return; }
        if (assistant.busy(player.getUniqueId())) { tell(player, "上一条 AI 问题仍在处理。"); return; }
        if (jobs.containsKey(player.getUniqueId()) || jobs.size() >= settings.maxCaptures()) {
            tell(player, "视觉采样忙碌，请稍后再试。"); return;
        }
        long now = System.currentTimeMillis(), previous = lastStarted.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < settings.cooldownMillis()) { tell(player, "视觉采样正在冷却，请稍后再试。"); return; }
        var eye = player.getEyeLocation();
        Camera camera = new Camera(new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()), eye.getYaw(), eye.getPitch(),
                settings.width(), settings.height(), settings.verticalFov());
        int viewChunks = Math.max(1, Math.min(player.getClientViewDistance(), player.getSendViewDistance()));
        int range = Math.min(settings.maxDistance(), viewChunks * 16);
        Job job = new Job(player, camera, range, tick, question, debug);
        jobs.put(job.player, job); lastStarted.put(job.player, now);
        tell(player, "正在重建第一人称场景，请保持位置和视线稳定。粒子仅用于显示射线命中点。");
    }

    public void tick() {
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
                cancel(job.player); tell(player, "位置或视线变化较大，本次重建已取消，请重新采样。"); continue;
            }
            if (tick - job.startedTick > settings.timeoutTicks()) {
                cancel(job.player); tell(player, "视觉重建超过时间预算，请降低分辨率或距离。"); continue;
            }
            if (job.rendering == null) { active.add(job); players.put(job.player, player); }
        }
        long deadline = System.nanoTime() + settings.nanosPerTick();
        int used = 0;
        // Round-robin across captures; quota/time budget is global, not multiplied by player count.
        while (!active.isEmpty() && used < settings.raysPerTick() && System.nanoTime() < deadline) {
            for (var iterator = active.iterator(); iterator.hasNext();) {
                Job job = iterator.next();
                if (used >= settings.raysPerTick() || System.nanoTime() >= deadline) break;
                int index = job.samples.size(), x = index % settings.width(), y = index / settings.width();
                try {
                    RaySample sample = job.raycaster.trace(job.camera, job.camera.ray(x, y), job.range);
                    job.samples.add(sample); used++;
                    int stride = settings.maxParticles() == 0 ? Integer.MAX_VALUE
                            : Math.max(1, settings.width() * settings.height() / settings.maxParticles());
                    if (job.debug && index % stride == 0 && job.particles < settings.maxParticles()
                            && sample.kind() == RaySample.Kind.HIT
                            && players.get(job.player).hasPermission("pandagpt.vision.debug")) {
                        ParticleFeedback.show(players.get(job.player), sample); job.particles++;
                    }
                    if (job.samples.size() == settings.width() * settings.height()) {
                        render(job); iterator.remove();
                    }
                } catch (RuntimeException e) {
                    cancel(job.player); iterator.remove();
                    tell(players.get(job.player), "射线采样失败，本次重建已取消。");
                    plugin.getLogger().warning("Vision sampling error: " + e.getClass().getSimpleName());
                }
            }
        }
    }

    private void render(Job job) {
        SceneRenderer.Frame frame = new SceneRenderer.Frame(job.player, job.id, job.world.getName(), job.camera,
                job.range, job.startedAt, System.currentTimeMillis(), job.samples);
        job.rendering = renderer.submit(() -> {
            try {
                SceneRenderer.Rendered image = SceneRenderer.render(frame);
                if (job.cancelled || closed) return;
                if (settings.saveImages()) save(job, image);
                if (job.cancelled || closed) return;
                plugin.getServer().getScheduler().runTask(plugin, () -> complete(job, image));
            } catch (IOException | RuntimeException e) {
                if (job.cancelled || closed) return;
                try {
                    plugin.getServer().getScheduler().runTask(plugin, () -> {
                        if (jobs.get(job.player) != job) return;
                        cancel(job.player);
                        Player player = plugin.getServer().getPlayer(job.player);
                        if (player != null) tell(player, "图片生成或保存失败，请联系管理员。");
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
    private void complete(Job job, SceneRenderer.Rendered image) {
        if (closed || job.cancelled || jobs.get(job.player) != job) return;
        jobs.remove(job.player);
        Player player = plugin.getServer().getPlayer(job.player);
        if (player == null || !player.isOnline() || !player.hasPermission("pandagpt.use") || !player.hasPermission("pandagpt.vision")) return;
        var eye = player.getEyeLocation();
        if (!player.getWorld().getUID().equals(job.world.getUID())
                || !job.camera.matches(new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()), eye.getYaw(), eye.getPitch(), settings)) {
            tell(player, "图片生成期间视角变化，旧画面不会发送给模型，请重新采样。"); return;
        }
        tell(player, settings.saveImages()
                ? "重建完成：captures/" + job.player + "/ 下包含 color.png、depth.png、labels.png 与 metadata.json。"
                : "重建完成；文件保存已关闭。");
        if (job.question != null) assistant.ask(player, job.question, image.summary(), image.image());
    }
    public void cancel(UUID player) {
        Job job = jobs.remove(player);
        if (job != null) { job.cancelled = true; if (job.rendering != null) job.rendering.cancel(true); }
        renderer.purge();
    }
    public void clearAll() { for (UUID id : List.copyOf(jobs.keySet())) cancel(id); }
    public String status() { return "vision=" + settings.enabled() + ", captures=" + jobs.size() + ", size=" + settings.width() + "x" + settings.height(); }
    private static void tell(Player player, String text) {
        player.sendMessage(Component.text("[Panda Vision] " + text, NamedTextColor.AQUA));
    }
    @Override public void close() { closed = true; clearAll(); renderer.shutdownNow(); }
}
