package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.*;
import cn.panda.pandagpt.awareness.RecipeKnowledge;
import cn.panda.pandagpt.service.AssistantService;
import cn.panda.pandagpt.vision.*;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.block.Block;
import java.util.*;

/** Main-thread orchestration. Ambient observations never call the paid model or save image sequences. */
public final class PerceptionController implements AutoCloseable {
    private static final class State {
        final QuietPolicy quiet; String mode = "assist", goal = ""; int quantity = 1;
        boolean loaded; long lastObserved, busyUntil; String status = "", latestSuggestion = "";
        GoalProgress.Progress progress; BossBar bar; Boolean insideBase;
        long barUntil;
        State(PerceptionSettings settings) { quiet = new QuietPolicy(settings.routinePerHalfHour(), settings.suggestionSeconds() * 1000L); }
    }
    private final ChatGPTIntegration plugin; private final PerceptionSettings settings;
    private final SpatialMemory memory; private final RecipeKnowledge recipes;
    private final List<RecipeKnowledge.Entry> recipeEntries;
    private final ObservationTimeline timeline; private final Map<UUID, State> states = new LinkedHashMap<>();
    private VisionController vision; private long ticks; private int cursor; private boolean closed;
    public PerceptionController(ChatGPTIntegration plugin, PerceptionSettings settings, SpatialMemory memory, RecipeKnowledge recipes) {
        this.plugin = plugin; this.settings = settings; this.memory = memory; this.recipes = recipes;
        recipeEntries = recipes.entries();
        timeline = new ObservationTimeline(settings.maxPlayers());
    }
    public void attach(VisionController vision) { this.vision = vision; vision.attach(this, settings); }
    public void tick() {
        if (closed || !settings.enabled() || ++ticks % 20 != 0) return;
        List<? extends Player> online = new ArrayList<>(plugin.getServer().getOnlinePlayers());
        if (online.isEmpty()) return;
        long now = System.currentTimeMillis();
        // At most four player inventories/state checks per second; perception jobs use VisionController's shared world-read budget.
        for (int n = 0; n < Math.min(4, online.size()); n++) {
            Player player = online.get(cursor++ % online.size());
            if (!player.hasPermission("pandagpt.use")) { remove(player); continue; }
            State s = state(player); if (s == null) continue;
            if (!s.loaded) continue; // Respect persisted quiet mode before any ambient observation.
            if (s.barUntil > 0 && now >= s.barUntil) { hide(player, s); s.barUntil = 0; }
            if (player.hasPermission("pandagpt.vision") && s.mode.equals("assist") && now >= s.busyUntil
                    && now - s.lastObserved >= settings.observeSeconds() * 1000L && !plugin.service().busy(player.getUniqueId())) {
                if (vision.observe(player)) s.lastObserved = now;
            }
            if (!s.goal.isEmpty()) updateGoal(player, s, now);
            if (settings.proactive()) {
                var candidate = s.quiet.poll(now, s.mode.equals("quiet"), now < s.busyUntil || plugin.service().busy(player.getUniqueId()), c -> valid(player, s, c, now));
                if (candidate != null) {
                    s.latestSuggestion = candidate.text();
                    if (candidate.urgent()) player.sendActionBar(Component.text(candidate.text(), NamedTextColor.YELLOW));
                    else player.sendMessage(Component.text("[Panda 建议] " + candidate.text(), NamedTextColor.GRAY));
                }
            }
        }
    }
    private State state(Player player) {
        UUID id = player.getUniqueId(); State s = states.get(id);
        if (s == null) { if (states.size() >= settings.maxPlayers()) return null; s = new State(settings); states.put(id, s); }
        var data = memory.view(id);
        if (!s.loaded && memory.ready(id)) {
            s.mode = data.mode(); s.goal = data.goal(); s.quantity = data.quantity();
            if (!s.goal.isEmpty() && !GoalProgress.valid(s.goal)) s.goal = "";
            s.loaded = true;
        }
        return s;
    }
    public void accept(Player player, Observation observation) {
        if (closed || !settings.enabled()) return;
        var differences = timeline.accept(player.getUniqueId(), observation); State s = state(player); if (s == null) return;
        long now = System.currentTimeMillis(); s.lastObserved = now;
        // Record one newly verified interactive landmark per observation, not hidden inventories or unseen world structure.
        for (var surface : observation.surfaces().objects()) if (SceneObjects.interactive(surface.material())) {
            var pos = surface.blocks().getFirst(); String name = surface.material() + "@" + pos.x() + "," + pos.y() + "," + pos.z();
            var existing = memory.view(player.getUniqueId()).landmarks().stream().filter(l -> l.world().equals(observation.world()) && l.position().equals(pos)).findFirst().orElse(null);
            if (existing == null || existing.verifiedAt() == 0 || now - existing.verifiedAt() >= 300000)
                memory.remember(player.getUniqueId(), new SpatialMemory.Landmark(existing == null ? name : existing.name(), observation.world(), pos,
                        surface.state(), existing == null ? "raycast_visible_surface" : existing.source(),
                        existing == null ? observation.time() : existing.observedAt(), observation.time()));
            break;
        }
        if (!settings.proactive() || s.mode.equals("quiet")) return;
        if (!s.goal.isEmpty() && differences.changedBlocks() > 0 && differences.events().size() > 0)
            s.latestSuggestion = "已观察到 " + differences.changedBlocks() + " 处方块状态变化；可用 /panda observe 查看。";
        var drop = observation.spatial().routes().stream().filter(r -> r.ahead() == 1 && r.lateral() == 0
                && (r.result().equals("DROP") || r.result().equals("HAZARD"))).findFirst().orElse(null);
        if (drop != null && player.getVelocity().getX() * observation.camera().forward().x()
                + player.getVelocity().getZ() * observation.camera().forward().z() > .04) {
            var p = player.getLocation(); var pos = new SceneObjects.Pos(p.getBlockX(), p.getBlockY(), p.getBlockZ());
            s.quiet.offer(new QuietPolicy.Candidate("local-risk:" + observation.world() + ":" + pos + ":" + drop.result(),
                    "前方局部碰撞检测：" + drop.result() + "，请留意落脚点。", s.goal, observation.world(), pos, now + 2000, 100, true), now);
        }
    }
    private void updateGoal(Player player, State s, long now) {
        var stock = player.getInventory().getStorageContents();
        var all = player.getInventory().getContents(); if (all == null) all = stock;
        var progress = GoalProgress.evaluate(s.goal, s.quantity, stock, all, recipeEntries);
        if (s.bar == null && !progress.complete()) {
            s.bar = BossBar.bossBar(Component.text(progress.label()), (float) progress.fraction(), BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
            if (s.mode.equals("assist")) player.showBossBar(s.bar);
        }
        String newStatus = progress.label() + "：" + progress.detail();
        if (!newStatus.equals(s.status)) {
            boolean becameReady = progress.ingredientsReady() && (s.progress == null || !s.progress.ingredientsReady());
            s.progress = progress; s.status = newStatus;
            if (s.bar != null) { s.bar.name(Component.text(progress.label() + (progress.ingredientsReady() ? " · 材料齐全" : ""))); s.bar.progress((float) progress.fraction()); }
            if (becameReady) offer(player, s, "ready:" + s.goal, "目标材料已齐，可以进行下一步。", now, null, 40);
            if (progress.complete()) {
                s.latestSuggestion = progress.label() + " 已完成；已核对背包数量。";
                if (s.bar != null) { s.bar.name(Component.text(progress.label() + " ✓")); s.bar.progress(1); s.barUntil = now + 3000; }
                s.quiet.clear(); s.goal = ""; persist(player, s); return;
            }
        }
        if (s.mode.equals("quiet")) return;
        Observation o = latest(player);
        if (progress.ingredientsReady() && progress.needsTable() && o != null) {
            var table = o.surfaces().objects().stream().filter(v -> v.material().equals("CRAFTING_TABLE") && v.distance() <= 5).findFirst().orElse(null);
            if (table != null) offer(player, s, "table:" + s.goal + ":" + table.blocks().getFirst(),
                    "目标材料已齐，眼前已观察到工作台，可以顺路制作。", now, table.blocks().getFirst(), 60);
        }
        SpatialMemory.Landmark base = memory.view(player.getUniqueId()).landmarks().stream().filter(l -> l.world().equals(player.getWorld().getUID())
                && Set.of("基地", "家", "base").contains(l.name())).findFirst().orElse(null);
        if (base != null && s.goal.equals("mine")) {
            var location = player.getLocation(); double dx = location.getX() - base.position().x(), dz = location.getZ() - base.position().z();
            boolean inside = dx * dx + dz * dz <= 64;
            if (Boolean.TRUE.equals(s.insideBase) && !inside && (GoalProgress.count(all, "TORCH") < 16 || !Arrays.stream(all)
                    .filter(Objects::nonNull).anyMatch(i -> i.getType().name().endsWith("_PICKAXE")))) {
                offer(player, s, "departure:" + base.position() + ":" + GoalProgress.count(all, "TORCH"),
                        "离开标记基地前，采矿准备：" + progress.detail(), now, null, 70);
            }
            s.insideBase = inside;
        }
    }
    private void offer(Player player, State s, String key, String text, long now, SceneObjects.Pos anchor, int score) {
        if (!settings.proactive()) return;
        s.quiet.offer(new QuietPolicy.Candidate(key, text, s.goal, player.getWorld().getUID(), anchor, now + 20000, score, false), now);
    }
    private boolean valid(Player player, State s, QuietPolicy.Candidate c, long now) {
        if (!player.isOnline() || !player.hasPermission("pandagpt.use") || !c.world().equals(player.getWorld().getUID()) || !c.goal().equals(s.goal)) return false;
        if ((c.key().startsWith("ready:") || c.key().startsWith("table:")) && (s.progress == null || !s.progress.ingredientsReady())) return false;
        if (c.key().startsWith("departure:")) {
            var stock = player.getInventory().getContents(); if (stock == null) stock = player.getInventory().getStorageContents();
            if (GoalProgress.count(stock, "TORCH") >= 16 && Arrays.stream(stock).filter(Objects::nonNull).anyMatch(i -> i.getType().name().endsWith("_PICKAXE"))) return false;
            boolean nearBase = memory.view(player.getUniqueId()).landmarks().stream().filter(l -> l.world().equals(c.world()) && Set.of("基地", "家", "base").contains(l.name()))
                    .anyMatch(l -> Math.hypot(player.getLocation().getX() - l.position().x(), player.getLocation().getZ() - l.position().z()) <= 12);
            if (!nearBase) return false;
        }
        if (c.anchor() != null) {
            Observation o = latest(player); if (o == null) return false;
            var location = player.getLocation(); double dx = location.getX() - c.anchor().x(), dz = location.getZ() - c.anchor().z();
            if (dx * dx + dz * dz > 36) return false;
            if (!c.urgent() && !o.observedBlocks().containsKey(c.anchor())) return false;
            if (c.urgent() && !validView(player, o)) return false;
        }
        return true;
    }
    public Observation latest(Player player) { return timeline.get(player.getUniqueId(), player.getWorld().getUID(), System.currentTimeMillis(), settings.sceneTtlSeconds() * 1000L); }
    public String context(Player player) { return context(player, true); }
    public String context(Player player, boolean scene) {
        if (!settings.enabled()) return "";
        long now = System.currentTimeMillis(); State s = state(player); Observation o = latest(player);
        String text = memory.context(player.getUniqueId(), player.getWorld().getUID(), now) + "\n" + timeline.context(player.getUniqueId())
                + (s == null ? "" : "\nExplicit goal=" + s.goal + "; progress=" + s.status);
        if (scene && o != null) text += "\nRecent sampled observation, may no longer match current camera:\n" + o.context();
        return text.length() > (scene ? 6000 : 3000) ? text.substring(0, scene ? 6000 : 3000) : text;
    }
    public AssistantService.VisualQuery visualQuery(Player player, Observation o) {
        return new AssistantService.VisualQuery(o.surfaces().objects().stream().map(SceneObjects.Surface::id).toList(),
                () -> validView(player, o), id -> focus(player, o, id));
    }
    private boolean validView(Player player, Observation o) {
        if (!player.isOnline() || !player.hasPermission("pandagpt.vision") || latest(player) != o) return false;
        var eye = player.getEyeLocation();
        return o.camera().matches(new Camera.Vec3(eye.getX(), eye.getY(), eye.getZ()), eye.getYaw(), eye.getPitch(), plugin.settings().vision());
    }
    public boolean focus(Player player, Observation o, int id) {
        var object = o.surfaces().get(id);
        if (object == null || !validView(player, o)) { tell(player, "对象或视角已失效，请重新观察。"); return false; }
        var pos = object.blocks().getFirst(); var world = player.getWorld();
        if (!player.getSentChunkKeys().contains(BlockRaycaster.chunkKey(pos.x() >> 4, pos.z() >> 4)) || !world.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) return false;
        if (!world.getBlockAt(pos.x(), pos.y(), pos.z()).getBlockData().getAsString().equals(object.state())) return false;
        tell(player, "选中 #" + id + " " + object.material() + "，距离约 " + Math.round(object.distance() * 10) / 10.0 + " 格，坐标 " + object.blocks().getFirst());
        if (player.hasPermission("pandagpt.vision.debug")) vision.mark(player, object, o);
        else tell(player, "粒子标记需要 pandagpt.vision.debug 权限。");
        return true;
    }
    public void command(Player player, String[] args) {
        if (!settings.enabled()) { tell(player, "感知模块未开启。"); return; }
        State s = state(player); if (s == null) { tell(player, "感知会话已满。"); return; }
        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("goal")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("clear")) { s.goal = ""; s.status = ""; s.progress = null; s.insideBase = null; s.quiet.clear(); hide(player, s); persist(player, s); tell(player, "目标已清除。"); return; }
            if (args.length >= 2) {
                String goal = GoalProgress.normalize(args[1]); int quantity = 1;
                try { if (args.length == 3) quantity = Integer.parseInt(args[2]); } catch (NumberFormatException ignored) { quantity = 0; }
                if (args.length > 3 || !GoalProgress.valid(goal) || quantity < 1 || quantity > 64) { tell(player, "/panda goal <物品ID|铁镐|mine> [1..64]"); return; }
                s.goal = goal; s.quantity = quantity; s.progress = null; s.status = ""; s.insideBase = null; s.quiet.clear(); s.loaded = true; s.barUntil = 0;
                hide(player, s); s.bar = BossBar.bossBar(Component.text("目标：" + goal), 0, BossBar.Color.BLUE, BossBar.Overlay.PROGRESS);
                if (s.mode.equals("assist")) player.showBossBar(s.bar);
                persist(player, s); updateGoal(player, s, System.currentTimeMillis()); tell(player, "已记录目标；只更新同一条状态，完成时核对背包。"); return;
            }
            tell(player, s.status.isEmpty() ? "没有目标。/panda goal <物品ID|mine> [数量]" : s.status); return;
        }
        if (root.equals("proactive")) {
            if (args.length == 2 && Set.of("on", "off").contains(args[1].toLowerCase(Locale.ROOT))) {
                s.mode = args[1].equalsIgnoreCase("on") ? "assist" : "quiet"; s.loaded = true; s.quiet.clear();
                if (s.bar != null) { if (s.mode.equals("quiet")) player.hideBossBar(s.bar); else player.showBossBar(s.bar); }
                if (s.mode.equals("quiet")) vision.cancel(player.getUniqueId());
                persist(player, s); tell(player, "主动辅助：" + s.mode + "。普通建议有频率上限，自动观察不调用模型。"); return;
            }
            tell(player, "模式=" + s.mode + "；目标=" + s.status + "；最近建议=" + s.latestSuggestion); return;
        }
        if (root.equals("observe")) {
            Observation o = latest(player);
            if (o == null) tell(player, vision.observe(player) ? "已安排一次低分辨率采样；完成后再次 /panda observe 查看。" : "无法安排观察：请检查视觉权限、模块开关或采样是否忙碌。");
            else { tell(player, o.surfaces().context()); tell(player, o.spatial().context()); tell(player, timeline.context(player.getUniqueId()));
                for (var e : o.entities()) tell(player, e.type() + " " + e.item() + " " + e.visibility() + (e.targetingPlayer() ? "，服务器目标为你" : "")); }
            return;
        }
        if (root.equals("memory")) {
            if (args.length >= 3 && args[1].equalsIgnoreCase("mark")) {
                String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length)); if (name.length() > 64) { tell(player, "名称最多 64 字符。"); return; }
                Observation o = latest(player); SceneObjects.Surface target = o == null ? null : o.surfaces().objects().stream()
                        .min(Comparator.comparingDouble(a -> Math.hypot(a.screenX() - .5, a.screenY() - .5))).orElse(null);
                var l = player.getLocation(); SceneObjects.Pos pos = target != null && validView(player, o) && !Set.of("基地", "家", "base").contains(name)
                        ? target.blocks().getFirst() : new SceneObjects.Pos(l.getBlockX(), l.getBlockY(), l.getBlockZ());
                long now = System.currentTimeMillis(); boolean accepted = memory.remember(player.getUniqueId(), new SpatialMemory.Landmark(name, player.getWorld().getUID(), pos,
                        target != null && pos.equals(target.blocks().getFirst()) ? target.state() : "player_marked_location", "explicit_player_mark", now, now));
                tell(player, accepted ? "地点保存已排队；/panda memory list 查看写入结果。" : "记忆保存队列不可用。"); return;
            }
            if (args.length == 2 && args[1].equalsIgnoreCase("clear")) {
                memory.edit(player.getUniqueId(), d -> new SpatialMemory.Data(List.of(), d.mode(), d.goal(), d.quantity())); tell(player, "地点记忆清除已排队。"); return;
            }
            if (args.length >= 3 && args[1].equalsIgnoreCase("forget")) {
                String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                memory.edit(player.getUniqueId(), d -> new SpatialMemory.Data(d.landmarks().stream().filter(l -> !l.name().equals(name)).toList(), d.mode(), d.goal(), d.quantity()));
                tell(player, "删除地点已排队。"); return;
            }
            if (!memory.ready(player.getUniqueId())) { memory.view(player.getUniqueId()); tell(player, "记忆正在加载或不可用，请稍后查看。"); }
            else {
                var landmarks = memory.view(player.getUniqueId()).landmarks().stream().filter(l -> l.world().equals(player.getWorld().getUID())).toList();
                if (landmarks.isEmpty()) tell(player, "当前世界没有地点记忆。");
                for (var l : landmarks) tell(player, l.name() + " " + l.position() + "；来源=" + l.source()
                        + (l.verifiedAt() == 0 ? "；已失效，需复查" : "；上次验证=" + java.time.Instant.ofEpochMilli(l.verifiedAt())));
            }
        }
    }
    private void persist(Player player, State s) {
        String mode = s.mode, goal = s.goal; int quantity = s.quantity;
        if (!memory.edit(player.getUniqueId(), d -> new SpatialMemory.Data(d.landmarks(), mode, goal, quantity))) tell(player, "设置保存队列不可用；本次在线设置仍生效。");
    }
    public void busy(Player player) { State s = states.get(player.getUniqueId()); if (s != null) s.busyUntil = System.currentTimeMillis() + 5000; }
    public void interacted(Player player, Block block) {
        busy(player); if (!settings.enabled() || !player.hasPermission("pandagpt.use") || !SceneObjects.interactive(block.getType().name())) return;
        long now = System.currentTimeMillis(); SceneObjects.Pos pos = new SceneObjects.Pos(block.getX(), block.getY(), block.getZ());
        memory.remember(player.getUniqueId(), new SpatialMemory.Landmark(block.getType().name() + "@" + pos.x() + "," + pos.y() + "," + pos.z(),
                block.getWorld().getUID(), pos, block.getBlockData().getAsString(), "player_block_interaction", now,
                block.getType().name().endsWith("_DOOR") ? 0 : now));
    }
    public void changed(UUID world, SceneObjects.Pos pos) { timeline.invalidate(world, pos); memory.invalidate(world, pos); if (vision != null) vision.changed(world, pos); }
    public void remove(Player player) { State s = states.remove(player.getUniqueId()); if (s != null) hide(player, s); timeline.remove(player.getUniqueId()); memory.release(player.getUniqueId()); }
    public void clearTransient() { timeline.clear(); states.values().forEach(s -> s.quiet.clear()); }
    private void hide(Player player, State s) { if (s.bar != null) { player.hideBossBar(s.bar); s.bar = null; } }
    private static void tell(Player player, String text) { player.sendMessage(Component.text("[Panda 感知] " + text, NamedTextColor.AQUA)); }
    public String status() { return "perception=" + settings.enabled() + ", tracked=" + states.size() + ", proactive=" + settings.proactive(); }
    @Override public void close() {
        closed = true; for (UUID id : List.copyOf(states.keySet())) { Player p = plugin.getServer().getPlayer(id); if (p != null) { State s = states.get(id); hide(p, s); } }
        states.clear(); timeline.clear();
    }
}
