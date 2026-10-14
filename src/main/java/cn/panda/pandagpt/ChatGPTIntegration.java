package cn.panda.pandagpt;

import cn.panda.pandagpt.ai.HttpAiProvider;
import cn.panda.pandagpt.awareness.RecipeKnowledge;
import cn.panda.pandagpt.commands.PandaCommand;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.listeners.ChatListener;
import cn.panda.pandagpt.service.AssistantService;
import cn.panda.pandagpt.util.PandaLogger;
import cn.panda.pandagpt.vision.VisionController;
import cn.panda.pandagpt.perception.*;
import cn.panda.pandagpt.listeners.PerceptionListener;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Objects;
import java.util.regex.Pattern;

public final class ChatGPTIntegration extends JavaPlugin {
    private volatile Settings settings;
    private volatile Pattern triggerPattern;
    private AssistantService service;
    private VisionController vision;
    private PerceptionController perception;
    private SpatialMemory memory;
    private BukkitTask cleanup;
    private BukkitTask visionTick;

    @Override public void onEnable() {
        saveDefaultConfig();
        try {
            Files.createDirectories(getDataFolder().toPath());
            if (!getDataFolder().toPath().resolve("knowledge.md").toFile().exists()) saveResource("knowledge.md", false);
            loadService();
            PandaCommand command = new PandaCommand(this);
            Objects.requireNonNull(getCommand("panda")).setExecutor(command);
            Objects.requireNonNull(getCommand("panda")).setTabCompleter(command);
            Objects.requireNonNull(getCommand("pandaadmin")).setExecutor(command);
            Objects.requireNonNull(getCommand("pandaadmin")).setTabCompleter(command);
            getServer().getPluginManager().registerEvents(new ChatListener(this), this);
            getServer().getPluginManager().registerEvents(new PerceptionListener(this), this);
            cleanup = getServer().getScheduler().runTaskTimer(this, () -> service.expire(), 1200, 1200);
            visionTick = getServer().getScheduler().runTaskTimer(this, () -> { var budget = vision.newBudget(); vision.tick(budget); perception.tick(budget); vision.finishTick(budget); }, 1, 1);
            getLogger().info("PandaGPT enabled: Paper 26.3 / Java 25; " + service.status());
            if (settings.apiKey().isBlank()) getLogger().warning("Selected AI provider has no API key; questions will be rejected until configured.");
        } catch (Exception e) {
            getLogger().severe("Cannot enable PandaGPT: " + safeConfigurationError(e));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** Validate and construct first; an invalid reload leaves the existing service intact. */
    public void loadService() throws IOException {
        Path path = getDataFolder().toPath().resolve("config.yml");
        YamlConfiguration candidate = new YamlConfiguration();
        try { candidate.load(path.toFile()); }
        catch (org.bukkit.configuration.InvalidConfigurationException e) { throw new IOException("config.yml YAML is invalid"); }
        int version = candidate.getInt("config_version", 1);
        if (version > 2) throw new IllegalArgumentException("config_version is newer than this plugin supports");
        if (version < 2) {
            Path backup = getDataFolder().toPath().resolve("config-v1-backup.yml");
            if (!Files.exists(backup)) Files.copy(path, backup);
            if (!candidate.contains("panda.history_rounds"))
                candidate.set("panda.history_rounds", Math.max(0, candidate.getInt("panda.history_size", 5) / 2));
            candidate.set("panda.history_size", null);
            candidate.set("config_version", 2);
            getLogger().info("v1 config backed up. Existing models/proxy are retained; review them before enabling requests.");
        }
        try (InputStream defaultsStream = Objects.requireNonNull(getResource("config.yml"));
             Reader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            candidate.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        candidate.options().copyDefaults(true);
        Settings next = Settings.load(candidate);
        if (next.maxPromptChars() >= next.contextBudget())
            throw new IllegalArgumentException("max_prompt_chars must be smaller than context_char_budget");
        Path knowledgePath = getDataFolder().toPath().resolve("knowledge.md");
        if (Files.size(knowledgePath) > 32768) throw new IllegalArgumentException("knowledge.md must be at most 32 KiB");
        String knowledge = Files.readString(knowledgePath, StandardCharsets.UTF_8);
        if (next.personality().length() + knowledge.length() + 1000 >= next.contextBudget())
            throw new IllegalArgumentException("personality and knowledge.md leave insufficient context budget");
        RecipeKnowledge recipes = new RecipeKnowledge(getServer());
        PandaLogger log = new PandaLogger(getDataFolder().toPath(), next);
        HttpAiProvider provider;
        try { provider = new HttpAiProvider(next); }
        catch (RuntimeException e) { log.close(); throw e; }
        AssistantService replacement = new AssistantService(this, next, provider, log, recipes, knowledge);
        VisionController nextVision = new VisionController(this, next.vision(), replacement, getDataFolder().toPath());
        if (memory == null) memory = new SpatialMemory(getDataFolder().toPath().resolve("memory"), next.perception().maxPlayers(), message -> getLogger().warning(message));
        PerceptionController nextPerception = new PerceptionController(this, next.perception(), memory, recipes);
        nextPerception.attach(nextVision);
        try {
            saveAtomically(candidate, path);
        } catch (IOException e) {
            nextPerception.close(); nextVision.close(); replacement.close(); throw new IOException("Cannot save validated configuration");
        }
        AssistantService previous = service;
        VisionController previousVision = vision;
        PerceptionController previousPerception = perception;
        memory.setLimit(next.perception().maxPlayers());
        service = replacement;
        vision = nextVision;
        perception = nextPerception;
        settings = next;
        triggerPattern = Pattern.compile("^\\s*@?" + Pattern.quote(next.trigger())
                + "(?:\\s+|[,:：，]\\s*|$)(.*)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        if (previousPerception != null) previousPerception.close();
        if (previousVision != null) previousVision.close();
        if (previous != null) previous.close();
    }

    private static void saveAtomically(YamlConfiguration configuration, Path path) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "config-", ".tmp");
        try {
            configuration.save(temporary.toFile());
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
    public static String safeConfigurationError(Exception e) {
        // Do not expose URI/header/key-bearing low-level exception messages.
        return e instanceof IllegalArgumentException || e instanceof IOException ? e.getMessage() : e.getClass().getSimpleName();
    }
    public Settings settings() { return settings; }
    public Pattern triggerPattern() { return triggerPattern; }
    public AssistantService service() { return service; }
    public VisionController vision() { return vision; }
    public PerceptionController perception() { return perception; }
    public SpatialMemory memory() { return memory; }
    private static final Pattern VISUAL_REFERENCE = Pattern.compile("(?is).*(眼前|左边|右边|前面|这个|那个|这条路|这栋|in front|on the left|on the right|this block|that door).*");
    public void ask(org.bukkit.entity.Player player, String question) {
        if (settings.perception().enabled() && player.hasPermission("pandagpt.vision")
                && VISUAL_REFERENCE.matcher(question).matches())
            vision.capture(player, question, false);
        else service.ask(player, question);
    }
    @Override public void onDisable() {
        if (cleanup != null) cleanup.cancel();
        if (visionTick != null) visionTick.cancel();
        if (perception != null) { perception.close(); perception = null; }
        if (vision != null) { vision.close(); vision = null; }
        if (service != null) { service.close(); service = null; }
        if (memory != null) { memory.close(); memory = null; }
    }
}
