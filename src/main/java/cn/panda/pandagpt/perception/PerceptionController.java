package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.awareness.RecipeKnowledge;
import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Lang.Prefix;
import cn.panda.pandagpt.i18n.Msg;
import cn.panda.pandagpt.performance.TickBudget;
import cn.panda.pandagpt.util.FormatUtil;
import cn.panda.pandagpt.vision.BlockRaycaster;
import cn.panda.pandagpt.vision.Camera;
import cn.panda.pandagpt.vision.VisionController;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * 感知与主动辅助的主线程编排：环境观察、目标进度、安静建议与地点记忆命令。
 *
 * <p>环境观察从不调用付费模型，也不保存图片序列。线程：全部方法在服务器主线程调用。
 */
public final class PerceptionController implements AutoCloseable {
    /** 被视为“基地”的地点名称，用于采矿出发提醒。 */
    public static final Set<String> BASE_NAMES = Set.of("基地", "家", "base", "home");

    /** 辅助模式；持久化时使用小写 ID。 */
    enum AssistMode {
        ASSIST("assist"),
        QUIET("quiet");

        final String id;

        AssistMode(String id) {
            this.id = id;
        }

        static AssistMode of(String id) {
            return "quiet".equals(id) ? QUIET : ASSIST;
        }

        Msg label() {
            return Msg.of("mode." + id);
        }
    }

    private static final int TICK_INTERVAL = 5;
    private static final long BUSY_MILLIS = 5000;
    private static final long ROUTINE_TTL_MILLIS = 20000;
    private static final long RISK_TTL_MILLIS = 2000;
    private static final long LANDMARK_REFRESH_MILLIS = 300_000;

    /** 每名在线玩家的感知状态。 */
    private static final class State {
        final QuietPolicy quiet;
        AssistMode mode = AssistMode.ASSIST;
        String goal = "";
        int quantity = 1;
        boolean loaded;
        long lastObserved;
        long busyUntil;
        long barUntil;
        GoalProgress.Progress progress;
        Msg latestSuggestion;
        BossBar bar;
        Boolean insideBase;

        State(PerceptionSettings settings) {
            quiet = new QuietPolicy(settings.routinePerHalfHour(), settings.suggestionSeconds() * 1000L);
        }

        Msg status() {
            return progress == null ? null : Msg.of("goal.status", progress.label(), progress.detail());
        }

        void resetGoal() {
            progress = null;
            insideBase = null;
            quiet.clear();
        }
    }

    private final ChatGPTIntegration plugin;
    private final PerceptionSettings settings;
    private final SpatialMemory memory;
    private final RecipeKnowledge recipes;
    private final Clock clock;
    private final ObservationTimeline timeline;
    private final Map<UUID, State> states = new LinkedHashMap<>();
    private final List<UUID> online = new ArrayList<>();
    private final Set<UUID> onlineSet = new HashSet<>();
    private VisionController vision;
    private long ticks;
    private int cursor;
    private boolean closed;

    public PerceptionController(
            ChatGPTIntegration plugin, PerceptionSettings settings, SpatialMemory memory, RecipeKnowledge recipes) {
        this(plugin, settings, memory, recipes, Clock.systemUTC());
    }

    public PerceptionController(
            ChatGPTIntegration plugin,
            PerceptionSettings settings,
            SpatialMemory memory,
            RecipeKnowledge recipes,
            Clock clock) {
        this.plugin = plugin;
        this.settings = settings;
        this.memory = memory;
        this.recipes = recipes;
        this.clock = clock;
        timeline = new ObservationTimeline(settings.maxPlayers());
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            joined(player);
        }
    }

    public void attach(VisionController vision) {
        this.vision = vision;
        vision.attach(this, settings);
    }

    public void tick() {
        tick(new TickBudget(2_000_000, 8192, true));
    }

    /** 每 5 tick 轮询一名玩家，即每秒最多检查四名玩家的背包与状态；观察任务共享视觉模块的世界读取预算。 */
    public void tick(TickBudget budget) {
        if (closed || !settings.enabled() || ++ticks % TICK_INTERVAL != 0) {
            return;
        }
        if (!budget.background() || !budget.available() || online.isEmpty()) {
            return;
        }
        long now = clock.millis();
        if (cursor >= online.size()) {
            cursor = 0;
        }
        Player player = plugin.getServer().getPlayer(online.get(cursor++));
        if (player == null || !player.isOnline()) {
            return;
        }
        if (!player.hasPermission("pandagpt.use")) {
            forget(player);
            return;
        }
        State s = state(player);
        // 持久化的安静模式加载完成前，不做任何环境观察。
        if (s == null || !s.loaded) {
            return;
        }
        if (s.barUntil > 0 && now >= s.barUntil) {
            hide(player, s);
            s.barUntil = 0;
        }
        boolean waiting = plugin.service().busy(player.getUniqueId());
        if (player.hasPermission("pandagpt.vision")
                && s.mode == AssistMode.ASSIST
                && now >= s.busyUntil
                && now - s.lastObserved >= settings.observeSeconds() * 1000L
                && !waiting
                && vision.observe(player)) {
            s.lastObserved = now;
        }
        if (!s.goal.isEmpty()) {
            updateGoal(player, s, now, budget::available);
        }
        if (settings.proactive()) {
            var candidate = s.quiet.poll(
                    now, s.mode == AssistMode.QUIET, now < s.busyUntil || waiting, c -> valid(player, s, c, now));
            if (candidate != null) {
                s.latestSuggestion = candidate.text();
                if (candidate.urgent()) {
                    // 紧急风险需要立即显示，来不及等模型回答。
                    player.sendActionBar(Component.text(Lang.text(player, candidate.text()), NamedTextColor.YELLOW));
                } else {
                    // 普通建议交给主动式 Agent 决定是否开口、说什么；不可用时退回内置文本。
                    plugin.service().proactive(player, candidate);
                }
            }
            vitals(player, s, now);
            nightfall(player, s, now);
        }
    }

    // 生命或饥饿值偏低时提出候选；同一事实半小时内只提醒一次。
    private void vitals(Player player, State s, long now) {
        if (player.getHealth() <= 6) {
            offer(player, s, QuietPolicy.Kind.VITALS, "vitals:health", Msg.of("suggest.low_health"), now, null, 55);
        } else if (player.getFoodLevel() <= 6) {
            offer(player, s, QuietPolicy.Kind.VITALS, "vitals:food", Msg.of("suggest.low_food"), now, null, 50);
        }
    }

    // 主世界入夜时仍远离家，提醒一次；每个游戏日最多一次。
    private void nightfall(Player player, State s, long now) {
        var world = player.getWorld();
        long time = world.getTime();
        if (world.getEnvironment() != org.bukkit.World.Environment.NORMAL || time < 12500 || time > 13500) {
            return;
        }
        SpatialMemory.Landmark home = base(player, world.getUID());
        if (home == null) {
            return;
        }
        var l = player.getLocation();
        double distance = Math.hypot(
                l.getX() - home.position().x(), l.getZ() - home.position().z());
        if (distance > 96) {
            offer(
                    player,
                    s,
                    QuietPolicy.Kind.NIGHTFALL,
                    "nightfall:" + world.getFullTime() / 24000,
                    Msg.of("suggest.nightfall", Math.round(distance)),
                    now,
                    null,
                    45);
        }
    }

    private State state(Player player) {
        UUID id = player.getUniqueId();
        State s = states.get(id);
        if (s == null) {
            if (states.size() >= settings.maxPlayers()) {
                return null;
            }
            s = new State(settings);
            states.put(id, s);
        }
        if (s.loaded) {
            return s;
        }
        var data = memory.view(id);
        if (memory.ready(id)) {
            s.mode = AssistMode.of(data.mode());
            s.goal = data.goal();
            s.quantity = data.quantity();
            if (!s.goal.isEmpty() && !GoalProgress.valid(s.goal)) {
                s.goal = "";
            }
            s.loaded = true;
        }
        return s;
    }

    /** 接收一次完成的观察：更新时间线、记录可交互地标，并生成局部风险候选。 */
    public void accept(Player player, Observation observation) {
        if (closed || !settings.enabled()) {
            return;
        }
        var differences = timeline.accept(player.getUniqueId(), observation);
        State s = state(player);
        if (s == null) {
            return;
        }
        long now = clock.millis();
        s.lastObserved = now;
        rememberInteractive(player, observation, now);
        if (!settings.proactive() || s.mode == AssistMode.QUIET) {
            return;
        }
        if (!s.goal.isEmpty()
                && differences.changedBlocks() > 0
                && !differences.events().isEmpty()) {
            s.latestSuggestion = Msg.of("suggest.changes", differences.changedBlocks());
        }
        var drop = observation.spatial().routes().stream()
                .filter(r -> r.ahead() == 1
                        && r.lateral() == 0
                        && (r.result().equals("DROP") || r.result().equals("HAZARD")))
                .findFirst()
                .orElse(null);
        if (drop != null && forwardSpeed(player, observation) > .04) {
            var p = player.getLocation();
            var pos = new SceneObjects.Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
            s.quiet.offer(
                    new QuietPolicy.Candidate(
                            QuietPolicy.Kind.LOCAL_RISK,
                            "local-risk:" + observation.world() + ":" + pos + ":" + drop.result(),
                            Msg.of("suggest.local_risk", Msg.of("route." + drop.result())),
                            s.goal,
                            observation.world(),
                            pos,
                            now + RISK_TTL_MILLIS,
                            100,
                            true),
                    now);
        }
    }

    // 玩家沿观察方向的水平速度；只有正在朝风险方向移动时才提示。
    private static double forwardSpeed(Player player, Observation observation) {
        var velocity = player.getVelocity();
        var forward = observation.camera().forward();
        return velocity.getX() * forward.x() + velocity.getZ() * forward.z();
    }

    // 每次观察最多记录一个新确认的可交互地标，不记录隐藏的容器内容或未观察到的结构。
    private void rememberInteractive(Player player, Observation observation, long now) {
        for (var surface : observation.surfaces().objects()) {
            if (!SceneObjects.interactive(surface.material())) {
                continue;
            }
            var pos = surface.blocks().getFirst();
            var existing = memory.view(player.getUniqueId()).landmarks().stream()
                    .filter(l -> l.world().equals(observation.world())
                            && l.position().equals(pos))
                    .findFirst()
                    .orElse(null);
            if (existing == null
                    || existing.verifiedAt() == 0
                    || now - existing.verifiedAt() >= LANDMARK_REFRESH_MILLIS) {
                String name = surface.material() + "@" + pos.x() + "," + pos.y() + "," + pos.z();
                memory.remember(
                        player.getUniqueId(),
                        new SpatialMemory.Landmark(
                                existing == null ? name : existing.name(),
                                observation.world(),
                                pos,
                                surface.state(),
                                existing == null ? "raycast_visible_surface" : existing.source(),
                                existing == null ? observation.time() : existing.observedAt(),
                                observation.time()));
            }
            return;
        }
    }

    private void updateGoal(Player player, State s, long now) {
        long deadline = System.nanoTime() + 1_000_000;
        updateGoal(player, s, now, () -> System.nanoTime() < deadline);
    }

    private void updateGoal(Player player, State s, long now, BooleanSupplier permit) {
        ItemStack[] stock = player.getInventory().getStorageContents();
        ItemStack[] all = player.getInventory().getContents();
        if (all == null) {
            all = stock;
        }
        var progress = GoalProgress.evaluate(s.goal, s.quantity, stock, all, recipes.forTarget(s.goal), permit);
        if (s.bar == null && !progress.complete()) {
            s.bar = BossBar.bossBar(
                    Component.text(Lang.text(player, progress.label())),
                    (float) progress.fraction(),
                    BossBar.Color.BLUE,
                    BossBar.Overlay.PROGRESS);
            if (s.mode == AssistMode.ASSIST) {
                player.showBossBar(s.bar);
            }
        }
        // 只有状态文本变化时才更新同一条 BossBar 并生成候选，避免重复提示。
        Msg previous = s.status();
        boolean changed =
                previous == null || !previous.equals(Msg.of("goal.status", progress.label(), progress.detail()));
        if (changed) {
            boolean becameReady = progress.ingredientsReady() && (s.progress == null || !s.progress.ingredientsReady());
            s.progress = progress;
            if (s.bar != null) {
                Msg name = progress.ingredientsReady() ? Msg.of("goal.bar_ready", progress.label()) : progress.label();
                s.bar.name(Component.text(Lang.text(player, name)));
                s.bar.progress((float) progress.fraction());
            }
            if (becameReady) {
                offer(player, s, QuietPolicy.Kind.READY, "ready:" + s.goal, Msg.of("suggest.ready"), now, null, 40);
            }
            if (progress.complete()) {
                s.latestSuggestion = Msg.of("goal.completed", progress.label());
                if (s.bar != null) {
                    s.bar.name(Component.text(Lang.text(player, Msg.of("goal.bar_done", progress.label()))));
                    s.bar.progress(1);
                    s.barUntil = now + 3000;
                }
                s.quiet.clear();
                s.goal = "";
                persist(player, s);
                return;
            }
        }
        if (s.mode == AssistMode.QUIET) {
            return;
        }
        Observation o = latest(player);
        if (progress.ingredientsReady() && progress.needsTable() && o != null) {
            var table = o.surfaces().objects().stream()
                    .filter(v -> v.material().equals("CRAFTING_TABLE") && v.distance() <= 5)
                    .findFirst()
                    .orElse(null);
            if (table != null) {
                var anchor = table.blocks().getFirst();
                offer(
                        player,
                        s,
                        QuietPolicy.Kind.TABLE,
                        "table:" + s.goal + ":" + anchor,
                        Msg.of("suggest.table"),
                        now,
                        anchor,
                        60);
            }
        }
        if (s.goal.equals(GoalProgress.MINE)) {
            departureCheck(player, s, progress, all, now);
        }
    }

    // 从标记的基地 8 格范围内离开且准备不足时，提示采矿准备情况。
    private void departureCheck(Player player, State s, GoalProgress.Progress progress, ItemStack[] all, long now) {
        SpatialMemory.Landmark base = base(player, player.getWorld().getUID());
        if (base == null) {
            return;
        }
        var location = player.getLocation();
        double dx = location.getX() - base.position().x();
        double dz = location.getZ() - base.position().z();
        boolean inside = dx * dx + dz * dz <= 64;
        int torches = GoalProgress.count(all, "TORCH");
        if (Boolean.TRUE.equals(s.insideBase) && !inside && (torches < 16 || !GoalProgress.hasPickaxe(all))) {
            offer(
                    player,
                    s,
                    QuietPolicy.Kind.DEPARTURE,
                    "departure:" + base.position() + ":" + torches,
                    Msg.of("suggest.departure", progress.detail()),
                    now,
                    null,
                    70);
        }
        s.insideBase = inside;
    }

    private SpatialMemory.Landmark base(Player player, UUID world) {
        return memory.view(player.getUniqueId()).landmarks().stream()
                .filter(l -> l.world().equals(world) && isHome(l))
                .findFirst()
                .orElse(null);
    }

    private void offer(
            Player player,
            State s,
            QuietPolicy.Kind kind,
            String key,
            Msg text,
            long now,
            SceneObjects.Pos anchor,
            int score) {
        if (!settings.proactive()) {
            return;
        }
        s.quiet.offer(
                new QuietPolicy.Candidate(
                        kind,
                        key,
                        text,
                        s.goal,
                        player.getWorld().getUID(),
                        anchor,
                        now + ROUTINE_TTL_MILLIS,
                        score,
                        false),
                now);
    }

    // 发送前复核候选是否仍然成立：目标未变、材料仍齐全、仍在相关位置附近等。
    private boolean valid(Player player, State s, QuietPolicy.Candidate c, long now) {
        if (!player.isOnline()
                || !player.hasPermission("pandagpt.use")
                || !c.world().equals(player.getWorld().getUID())
                || !c.goal().equals(s.goal)) {
            return false;
        }
        if ((c.kind() == QuietPolicy.Kind.READY || c.kind() == QuietPolicy.Kind.TABLE)
                && (s.progress == null || !s.progress.ingredientsReady())) {
            return false;
        }
        if (c.kind() == QuietPolicy.Kind.VITALS && player.getHealth() > 6 && player.getFoodLevel() > 6) {
            return false;
        }
        if (c.kind() == QuietPolicy.Kind.NIGHTFALL && player.getWorld().getTime() < 12500) {
            return false;
        }
        if (c.kind() == QuietPolicy.Kind.DEPARTURE) {
            ItemStack[] stock = player.getInventory().getContents();
            if (stock == null) {
                stock = player.getInventory().getStorageContents();
            }
            if (GoalProgress.count(stock, "TORCH") >= 16 && GoalProgress.hasPickaxe(stock)) {
                return false;
            }
            var location = player.getLocation();
            SpatialMemory.Landmark base = base(player, c.world());
            if (base == null
                    || Math.hypot(
                                    location.getX() - base.position().x(),
                                    location.getZ() - base.position().z())
                            > 12) {
                return false;
            }
        }
        if (c.anchor() != null) {
            Observation o = latest(player);
            if (o == null) {
                return false;
            }
            var location = player.getLocation();
            double dx = location.getX() - c.anchor().x();
            double dz = location.getZ() - c.anchor().z();
            if (dx * dx + dz * dz > 36) {
                return false;
            }
            if (!c.urgent() && !o.observedBlocks().containsKey(c.anchor())) {
                return false;
            }
            if (c.urgent() && !validView(player, o)) {
                return false;
            }
        }
        return true;
    }

    public Observation latest(Player player) {
        return timeline.get(
                player.getUniqueId(), player.getWorld().getUID(), clock.millis(), settings.sceneTtlSeconds() * 1000L);
    }

    public String context(Player player) {
        return context(player, true);
    }

    /** 给模型的感知上下文：历史地点、近期变化、目标进度，以及可选的最近一次观察。 */
    public String context(Player player, boolean scene) {
        if (!settings.enabled()) {
            return "";
        }
        long now = clock.millis();
        State s = state(player);
        Observation o = latest(player);
        Msg status = s == null ? null : s.status();
        String text = memory.context(player.getUniqueId(), player.getWorld().getUID(), now) + "\n"
                + timeline.context(player.getUniqueId())
                + (s == null
                        ? ""
                        : "\nAssistance mode=" + s.mode.id + "; explicit goal=" + s.goal + "; progress="
                                + (status == null ? "" : Lang.text(player, status)));
        if (scene && o != null) {
            text += "\nRecent sampled observation, may no longer match current camera:\n" + o.context();
        }
        return FormatUtil.truncate(text, scene ? 6000 : 3000);
    }

    private boolean validView(Player player, Observation o) {
        if (!player.isOnline() || !player.hasPermission("pandagpt.vision") || latest(player) != o) {
            return false;
        }
        var eye = player.getEyeLocation();
        return o.camera()
                .matches(
                        new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()),
                        eye.getYaw(),
                        eye.getPitch(),
                        plugin.settings().vision());
    }

    /**
     * 选中最近一次观察中的对象：核对视角与当前方块状态，有调试权限时用私有粒子标记轮廓。
     *
     * @return 给模型看的英文结果
     */
    public String focusLatest(Player player, int id) {
        Observation o = latest(player);
        if (o == null) {
            return "ERROR: no recent observation; call look first.";
        }
        var object = o.surfaces().get(id);
        if (object == null) {
            return "ERROR: object #" + id + " is not in the latest observation.";
        }
        if (!validView(player, o)) {
            return "ERROR: the player moved or turned since the observation; call look again.";
        }
        var pos = object.blocks().getFirst();
        var world = player.getWorld();
        if (!player.getSentChunkKeys().contains(BlockRaycaster.chunkKey(pos.x() >> 4, pos.z() >> 4))
                || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return "ERROR: the object's chunk is not loaded for the player.";
        }
        String state =
                world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData().getAsString();
        if (!state.equals(object.state())) {
            return "ERROR: the block changed since the observation (now " + state + ").";
        }
        boolean marked = player.hasPermission("pandagpt.vision.debug");
        if (marked) {
            vision.mark(player, object, o);
        }
        return "Verified #" + id + " " + object.material() + " at " + pos + ", distance "
                + Math.round(object.distance() * 10) / 10.0 + " blocks, state " + state
                + (marked ? "; outline highlighted with private particles." : "; no particle permission.");
    }

    /** 最近一次环境观察与变化，供 Agent 回忆周围情况。 */
    public String recall(Player player) {
        Observation o = latest(player);
        String changes = timeline.context(player.getUniqueId());
        if (o == null) {
            return "No recent observation is available. Use look to capture the current view."
                    + (changes.isEmpty() ? "" : "\n" + changes);
        }
        long age = Math.max(0, (clock.millis() - o.time()) / 1000);
        return "Observation from " + age + "s ago (may be stale):\n" + o.context()
                + (changes.isEmpty() ? "" : "\n" + changes);
    }

    /** 当前世界的地点列表（JSON 风格文本），包含距离，供 Agent 使用。 */
    public String listPlaces(Player player) {
        UUID id = player.getUniqueId();
        if (!memory.ready(id)) {
            memory.view(id);
            return "Memory is still loading; try again shortly.";
        }
        var l = player.getLocation();
        StringBuilder out = new StringBuilder();
        for (var landmark : memory.view(id).landmarks()) {
            if (!landmark.world().equals(player.getWorld().getUID())) {
                continue;
            }
            double distance = Math.hypot(
                    l.getX() - landmark.position().x(),
                    l.getZ() - landmark.position().z());
            out.append("- ")
                    .append(landmark.name())
                    .append(isHome(landmark) ? " [home]" : "")
                    .append(" at ")
                    .append(landmark.position())
                    .append(", ")
                    .append(Math.round(distance))
                    .append(" blocks away, source=")
                    .append(landmark.source())
                    .append(landmark.verifiedAt() == 0 ? ", CHANGED since recorded (recheck)" : "")
                    .append('\n');
        }
        return out.isEmpty() ? "No remembered places in this world." : out.toString();
    }

    /** 某个地点相对玩家的方位：距离、高度差、指南针方向与相对朝向。 */
    public String locatePlace(Player player, String name) {
        UUID world = player.getWorld().getUID();
        var landmark = memory.view(player.getUniqueId()).landmarks().stream()
                .filter(l -> l.world().equals(world)
                        && (l.name().equalsIgnoreCase(name) || (isHomeQuery(name) && isHome(l))))
                .findFirst()
                .orElse(null);
        if (landmark == null) {
            return "ERROR: no place named " + name + " in this world. Use list_places.";
        }
        var l = player.getLocation();
        double dx = landmark.position().x() + .5 - l.getX();
        double dz = landmark.position().z() + .5 - l.getZ();
        double dy = landmark.position().y() - l.getY();
        // Minecraft 偏航角：0 为南，90 为西，顺时针增加。
        double bearing = Math.floorMod((long) Math.round(Math.toDegrees(Math.atan2(-dx, dz))), 360);
        double relative = ((bearing - l.getYaw()) % 360 + 540) % 360 - 180;
        String[] compass = {"south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east"};
        String side = Math.abs(relative) <= 30
                ? "ahead"
                : Math.abs(relative) >= 150 ? "behind" : relative > 0 ? "to the right" : "to the left";
        return landmark.name() + " is " + Math.round(Math.hypot(dx, dz)) + " blocks "
                + compass[(int) Math.round(bearing / 45) % 8]
                + " (" + side + ", turn " + Math.round(relative) + " degrees), " + Math.round(dy) + " blocks "
                + (dy >= 0 ? "up" : "down") + ", at " + landmark.position()
                + (landmark.verifiedAt() == 0 ? ". The block there changed since it was recorded." : ".");
    }

    private static boolean isHomeQuery(String name) {
        return BASE_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** 当前目标与进度。 */
    public String goalStatus(Player player) {
        State s = state(player);
        if (s == null || s.goal.isEmpty()) {
            return s != null && s.progress != null && s.progress.complete()
                    ? "Last goal completed: " + Lang.text(player, s.status())
                    : "No goal is being tracked.";
        }
        Msg status = s.status();
        return "Goal " + s.goal + " x" + s.quantity
                + (status == null ? " (evaluating)" : ": " + Lang.text(player, status));
    }

    /** 感知模块是否可以执行意图。 */
    public boolean intentsAvailable() {
        return settings.enabled() && !closed;
    }

    // 模块关闭或会话已满时返回错误提示，否则返回 null。
    private Msg unavailable(Player player) {
        if (!intentsAvailable()) {
            return Msg.of("perception.disabled");
        }
        return state(player) == null ? Msg.of("perception.full") : null;
    }

    /** 设定目标；item 可以是物品 ID、常用中文名或 mine。返回给玩家的结果。 */
    public Msg setGoal(Player player, String item, int quantity) {
        Msg error = unavailable(player);
        if (error != null) {
            return error;
        }
        String goal = GoalProgress.normalize(item);
        if (!GoalProgress.valid(goal) || quantity < 1 || quantity > 64) {
            return Msg.of("intent.goal_invalid", item);
        }
        State s = state(player);
        s.goal = goal;
        s.quantity = quantity;
        s.resetGoal();
        s.loaded = true;
        s.barUntil = 0;
        hide(player, s);
        s.bar = BossBar.bossBar(
                Component.text(Lang.text(player, "goal.bar_initial", goal)),
                0,
                BossBar.Color.BLUE,
                BossBar.Overlay.PROGRESS);
        if (s.mode == AssistMode.ASSIST) {
            player.showBossBar(s.bar);
        }
        persist(player, s);
        updateGoal(player, s, clock.millis());
        return goal.equals(GoalProgress.MINE) ? Msg.of("intent.goal_mine") : Msg.of("intent.goal_set", goal, quantity);
    }

    public Msg clearGoal(Player player) {
        Msg error = unavailable(player);
        if (error != null) {
            return error;
        }
        State s = state(player);
        s.goal = "";
        s.resetGoal();
        hide(player, s);
        persist(player, s);
        return Msg.of("intent.goal_cleared");
    }

    /** 切换安静模式；安静时隐藏进度条并取消后台观察。 */
    public Msg setAssistance(Player player, boolean quiet) {
        Msg error = unavailable(player);
        if (error != null) {
            return error;
        }
        State s = state(player);
        s.mode = quiet ? AssistMode.QUIET : AssistMode.ASSIST;
        s.loaded = true;
        s.quiet.clear();
        if (s.bar != null) {
            if (quiet) {
                player.hideBossBar(s.bar);
            } else {
                player.showBossBar(s.bar);
            }
        }
        if (quiet) {
            vision.cancel(player.getUniqueId());
        }
        persist(player, s);
        return Msg.of(quiet ? "intent.quiet" : "intent.assist");
    }

    /**
     * 记住一个地点。
     *
     * @param lookingAt 当前视角仍然有效时，记录最接近画面中心的观察对象，否则记录脚下位置
     * @param home 记为玩家的家；家总是记录脚下位置，并替换此前的家
     */
    public Msg rememberPlace(Player player, String name, boolean lookingAt, boolean home) {
        Msg error = unavailable(player);
        if (error != null) {
            return error;
        }
        if (name.isBlank() || name.length() > 64) {
            return Msg.of("memory.name_too_long");
        }
        Observation o = latest(player);
        SceneObjects.Surface target = o == null || !lookingAt || home
                ? null
                : o.surfaces().objects().stream()
                        .min(Comparator.comparingDouble(a -> Math.hypot(a.screenX() - .5, a.screenY() - .5)))
                        .orElse(null);
        var l = player.getLocation();
        boolean useTarget = target != null && validView(player, o);
        SceneObjects.Pos pos = useTarget
                ? target.blocks().getFirst()
                : new SceneObjects.Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
        long now = clock.millis();
        boolean accepted = memory.remember(
                player.getUniqueId(),
                new SpatialMemory.Landmark(
                        name,
                        player.getWorld().getUID(),
                        pos,
                        useTarget ? target.state() : "player_marked_location",
                        home ? SpatialMemory.HOME_SOURCE : SpatialMemory.EXPLICIT_SOURCE,
                        now,
                        now));
        if (!accepted) {
            return Msg.of("memory.queue_unavailable");
        }
        return home ? Msg.of("intent.remembered_home", pos) : Msg.of("intent.remembered", name, pos);
    }

    /** 按名称忘记当前世界的地点，忽略大小写。 */
    public Msg forgetPlace(Player player, String name) {
        Msg error = unavailable(player);
        if (error != null) {
            return error;
        }
        UUID world = player.getWorld().getUID();
        boolean exists = memory.view(player.getUniqueId()).landmarks().stream()
                .anyMatch(l -> l.world().equals(world) && l.name().equalsIgnoreCase(name));
        if (!exists) {
            return Msg.of("intent.place_not_found", name);
        }
        memory.edit(
                player.getUniqueId(),
                d -> new SpatialMemory.Data(
                        d.landmarks().stream()
                                .filter(l ->
                                        !(l.world().equals(world) && l.name().equalsIgnoreCase(name)))
                                .toList(),
                        d.mode(),
                        d.goal(),
                        d.quantity()));
        return Msg.of("intent.forgot", name);
    }

    /**
     * 玩家睡床或使用重生锚设定重生点时，自动把该位置记为家，玩家无需任何命令。
     *
     * <p>玩家亲自标记过的家优先，不会被覆盖。
     */
    public void respawnSet(Player player, UUID world, SceneObjects.Pos pos) {
        if (!intentsAvailable() || !player.hasPermission("pandagpt.use")) {
            return;
        }
        State s = state(player);
        if (s == null) {
            return;
        }
        var landmarks = memory.view(player.getUniqueId()).landmarks().stream()
                .filter(l -> l.world().equals(world))
                .toList();
        if (landmarks.stream().anyMatch(l -> isHome(l) && !l.source().equals(SpatialMemory.AUTO_HOME_SOURCE))) {
            return;
        }
        var previous = landmarks.stream()
                .filter(l -> l.source().equals(SpatialMemory.AUTO_HOME_SOURCE))
                .findFirst()
                .orElse(null);
        if (previous != null && previous.position().equals(pos)) {
            return;
        }
        long now = clock.millis();
        boolean accepted = memory.remember(
                player.getUniqueId(),
                new SpatialMemory.Landmark(
                        Lang.text(player, "memory.auto_home_name"),
                        world,
                        pos,
                        "respawn_point",
                        SpatialMemory.AUTO_HOME_SOURCE,
                        now,
                        now));
        // 只在第一次自动记住家时轻声告知一次，之后静默更新。
        if (accepted && previous == null && s.mode == AssistMode.ASSIST) {
            Lang.send(player, Prefix.SUGGESTION, NamedTextColor.GRAY, "suggest.auto_home");
        }
    }

    /** 是否是玩家的家：名称为“家/基地/base”，或来源为家（手动或自动）。 */
    static boolean isHome(SpatialMemory.Landmark l) {
        return BASE_NAMES.contains(l.name().toLowerCase(Locale.ROOT))
                || SpatialMemory.HOME_SOURCES.contains(l.source());
    }

    private void persist(Player player, State s) {
        String mode = s.mode.id;
        String goal = s.goal;
        int quantity = s.quantity;
        if (!memory.edit(player.getUniqueId(), d -> new SpatialMemory.Data(d.landmarks(), mode, goal, quantity))) {
            tell(player, "perception.save_unavailable");
        }
    }

    /** 玩家受伤、放置或破坏方块后，普通建议推迟 5 秒。 */
    public void busy(Player player) {
        State s = states.get(player.getUniqueId());
        if (s != null) {
            s.busyUntil = clock.millis() + BUSY_MILLIS;
        }
    }

    /** 玩家右键可交互方块：记为自动地标。门的状态易变，记录后立即视为待复查。 */
    public void interacted(Player player, Block block) {
        busy(player);
        if (!settings.enabled()
                || !player.hasPermission("pandagpt.use")
                || !SceneObjects.interactive(block.getType().name())) {
            return;
        }
        long now = clock.millis();
        SceneObjects.Pos pos = new SceneObjects.Pos(block.getX(), block.getY(), block.getZ());
        memory.remember(
                player.getUniqueId(),
                new SpatialMemory.Landmark(
                        block.getType().name() + "@" + pos.x() + "," + pos.y() + "," + pos.z(),
                        block.getWorld().getUID(),
                        pos,
                        block.getBlockData().getAsString(),
                        "player_block_interaction",
                        now,
                        block.getType().name().endsWith("_DOOR") ? 0 : now));
    }

    /** 某坐标发生变化：使相关观察、地点与进行中的采样失效。 */
    public void changed(UUID world, SceneObjects.Pos pos) {
        timeline.invalidate(world, pos);
        memory.invalidate(world, pos);
        if (vision != null) {
            vision.changed(world, pos);
        }
    }

    public void joined(Player player) {
        if (onlineSet.add(player.getUniqueId())) {
            online.add(player.getUniqueId());
        }
    }

    public void remove(Player player) {
        if (onlineSet.remove(player.getUniqueId())) {
            online.remove(player.getUniqueId());
        }
        forget(player);
    }

    private void forget(Player player) {
        State s = states.remove(player.getUniqueId());
        if (s != null) {
            hide(player, s);
            memory.release(player.getUniqueId());
        }
        timeline.remove(player.getUniqueId());
    }

    public void clearTransient() {
        timeline.clear();
        states.values().forEach(s -> s.quiet.clear());
    }

    private void hide(Player player, State s) {
        if (s.bar != null) {
            player.hideBossBar(s.bar);
            s.bar = null;
        }
    }

    private static void tell(Player player, String key, Object... args) {
        Lang.send(player, Prefix.PERCEPTION, NamedTextColor.AQUA, key, args);
    }

    public String status() {
        return "perception=" + settings.enabled() + ", tracked=" + states.size() + ", proactive="
                + settings.proactive();
    }

    @Override
    public void close() {
        closed = true;
        for (var entry : states.entrySet()) {
            Player p = plugin.getServer().getPlayer(entry.getKey());
            if (p != null) {
                hide(p, entry.getValue());
            }
        }
        states.clear();
        timeline.clear();
    }
}
