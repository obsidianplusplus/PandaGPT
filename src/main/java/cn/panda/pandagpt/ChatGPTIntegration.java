package cn.panda.pandagpt;

import cn.panda.pandagpt.agent.PromptLibrary;
import cn.panda.pandagpt.agent.SkillLibrary;
import cn.panda.pandagpt.agent.ToolRegistry;
import cn.panda.pandagpt.agent.tools.AssistantTools;
import cn.panda.pandagpt.agent.tools.BuildTools;
import cn.panda.pandagpt.agent.tools.CommandTools;
import cn.panda.pandagpt.agent.tools.GoalTools;
import cn.panda.pandagpt.agent.tools.InspectTools;
import cn.panda.pandagpt.agent.tools.JournalTools;
import cn.panda.pandagpt.agent.tools.MemoryTools;
import cn.panda.pandagpt.agent.tools.NavigationTools;
import cn.panda.pandagpt.agent.tools.PlaceTools;
import cn.panda.pandagpt.agent.tools.ReminderTools;
import cn.panda.pandagpt.agent.tools.VisionTools;
import cn.panda.pandagpt.agent.tools.WorldTools;
import cn.panda.pandagpt.ai.HttpAiProvider;
import cn.panda.pandagpt.awareness.KnowledgeBase;
import cn.panda.pandagpt.awareness.RecipeKnowledge;
import cn.panda.pandagpt.commands.AdminCommand;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.features.FeatureService;
import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Localized;
import cn.panda.pandagpt.i18n.LocalizedException;
import cn.panda.pandagpt.i18n.LocalizedIOException;
import cn.panda.pandagpt.i18n.Messages;
import cn.panda.pandagpt.integration.Integration;
import cn.panda.pandagpt.integration.Integrations;
import cn.panda.pandagpt.listeners.ChatListener;
import cn.panda.pandagpt.listeners.FeatureListener;
import cn.panda.pandagpt.listeners.PerceptionListener;
import cn.panda.pandagpt.perception.PerceptionController;
import cn.panda.pandagpt.perception.SpatialMemory;
import cn.panda.pandagpt.service.AssistantService;
import cn.panda.pandagpt.util.PandaLogger;
import cn.panda.pandagpt.vision.VisionController;
import cn.panda.pandagpt.vision.VisionSettings;
import cn.panda.pandagpt.vision.texture.TextureLibrary;
import cn.panda.pandagpt.vision.texture.VanillaAssets;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * 插件入口：负责配置加载、重载交接与各模块的生命周期。
 *
 * <p>重载时先完整构建并校验新的模块，全部成功后才替换正在运行的实例；失败时保留原服务。线程：服务器主线程。
 */
public final class ChatGPTIntegration extends JavaPlugin {
    private static final int CONFIG_VERSION = 3;
    private static final int MAX_KNOWLEDGE_BYTES = 32768;

    private volatile Settings settings;
    private volatile Pattern triggerPattern;
    private AssistantService service;
    private VisionController vision;
    private PerceptionController perception;
    private SpatialMemory memory;
    private TextureLibrary textures;
    private volatile RecipeKnowledge recipes;
    private volatile KnowledgeBase knowledge;
    private FeatureService features;
    private volatile Integrations.Detected integrations = new Integrations.Detected(List.of(), List.of());
    private BukkitTask cleanup;
    private BukkitTask visionTick;
    // 正在下载的资源包，避免重载时重复下载同一个文件。
    private final Set<Path> downloading = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            Files.createDirectories(getDataFolder().toPath());
            if (!Files.exists(getDataFolder().toPath().resolve("knowledge.md"))) {
                saveResource("knowledge.md", false);
            }
            // 把内置语言、技能与提示词复制到数据目录，供服主修改；已存在的文件不会被覆盖。
            List<String> defaults = new java.util.ArrayList<>();
            Messages.SUPPORTED.forEach(language -> defaults.add("lang/" + language + ".yml"));
            SkillLibrary.BUILT_IN.forEach(skill -> defaults.add("skills/" + skill + ".md"));
            PromptLibrary.BUILT_IN.forEach(prompt -> defaults.add("prompts/" + prompt + ".md"));
            for (String resource : defaults) {
                if (!Files.exists(getDataFolder().toPath().resolve(resource))) {
                    saveResource(resource, false);
                }
            }
            loadService();
            downloadMissingAssets();
            // 玩家没有任何命令；唯一的命令是服主使用的 /pandaadmin。
            AdminCommand command = new AdminCommand(this);
            Objects.requireNonNull(getCommand("pandaadmin")).setExecutor(command);
            Objects.requireNonNull(getCommand("pandaadmin")).setTabCompleter(command);
            getServer().getPluginManager().registerEvents(new ChatListener(this), this);
            getServer().getPluginManager().registerEvents(new PerceptionListener(this), this);
            getServer().getPluginManager().registerEvents(new FeatureListener(this), this);
            cleanup = getServer().getScheduler().runTaskTimer(this, () -> service.expire(), 1200, 1200);
            visionTick = getServer()
                    .getScheduler()
                    .runTaskTimer(
                            this,
                            () -> {
                                var budget = vision.newBudget();
                                vision.tick(budget);
                                perception.tick(budget);
                                vision.finishTick(budget);
                                features.tick();
                            },
                            1,
                            1);
            getLogger().info(Lang.console("log.enabled", service.status()));
            if (settings.apiKey().isBlank()) {
                getLogger().warning(Lang.console("log.no_api_key"));
            }
        } catch (Exception e) {
            getLogger().severe(Lang.console("log.enable_failed", safeConfigurationError(e)));
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /** 先校验并构建全部新模块；任何一步失败都会保留正在运行的旧服务。 */
    public void loadService() throws IOException {
        Path folder = getDataFolder().toPath();
        Path path = folder.resolve("config.yml");
        YamlConfiguration candidate = new YamlConfiguration();
        try {
            candidate.load(path.toFile());
        } catch (InvalidConfigurationException e) {
            throw new LocalizedIOException("config.yaml_invalid");
        }
        int version = candidate.getInt("config_version", 1);
        if (version > CONFIG_VERSION) {
            throw new LocalizedException("config.version_newer");
        }
        if (version < 2) {
            migrateV1(candidate, path, folder);
        }
        if (version < 3) {
            migrateV2(candidate, path, folder);
        }
        try (InputStream defaultsStream = Objects.requireNonNull(getResource("config.yml"));
                Reader reader = new InputStreamReader(defaultsStream, StandardCharsets.UTF_8)) {
            candidate.setDefaults(YamlConfiguration.loadConfiguration(reader));
        }
        candidate.options().copyDefaults(true);
        Messages messages = Messages.load(
                folder,
                candidate.getString("panda.language.default", Messages.FALLBACK),
                candidate.getBoolean("panda.language.per_player", true));
        Settings next = Settings.load(candidate);
        if (next.maxPromptChars() >= next.contextBudget()) {
            throw new LocalizedException("config.prompt_exceeds_budget");
        }
        Path knowledgePath = folder.resolve("knowledge.md");
        if (Files.size(knowledgePath) > MAX_KNOWLEDGE_BYTES) {
            throw new LocalizedException("config.knowledge_too_large");
        }
        String knowledge = Files.readString(knowledgePath, StandardCharsets.UTF_8);
        if (next.personality().length() + 4000 >= next.contextBudget()) {
            throw new LocalizedException("config.knowledge_budget");
        }
        // 先探测外部插件，技能库据此启用 requires 满足的技能。
        Integrations.Detected detected = Integrations.detect(
                getServer(),
                next.features().disabledIntegrations(),
                message -> getLogger().warning(Lang.console("log.integration_failed", message)));
        Set<String> activeIntegrations = detected.ids();
        SkillLibrary nextSkills = SkillLibrary.load(
                folder.resolve("skills"),
                next.agent().disabledSkills(),
                requirement -> activeIntegrations.contains(requirement)
                        || getServer().getPluginManager().isPluginEnabled(requirement));
        PromptLibrary nextPrompts = PromptLibrary.load(folder.resolve("prompts"));
        RecipeKnowledge recipes = new RecipeKnowledge(getServer());
        PandaLogger log = new PandaLogger(folder, next);
        HttpAiProvider provider;
        try {
            provider = new HttpAiProvider(next);
        } catch (RuntimeException e) {
            log.close();
            throw e;
        }
        // 资源包与缓存大小不变时复用已解码的纹理。
        var packs = VisionController.texturePaths(next.vision(), folder);
        TextureLibrary previousTextures = textures;
        TextureLibrary nextTextures = previousTextures != null
                        && previousTextures.matches(packs, next.vision().textureCacheMiB())
                ? previousTextures
                : new TextureLibrary(packs, next.vision().textureCacheMiB());
        // 工具在调用时才读取当前的模块实例，因此重载后自动指向新的服务。
        ToolRegistry registry = new ToolRegistry()
                .register(AssistantTools.create(this))
                .register(PlaceTools.create(this))
                .register(GoalTools.create(this))
                .register(WorldTools.create(this))
                .register(VisionTools.create(this))
                .register(CommandTools.create(this))
                .register(BuildTools.create(this))
                .register(NavigationTools.create(this))
                .register(ReminderTools.create(this))
                .register(JournalTools.create(this))
                .register(MemoryTools.create(this))
                .register(InspectTools.create(this));
        for (Integration integration : detected.active()) {
            registry.register(integration.tools(this));
        }
        AssistantService replacement =
                new AssistantService(this, next, provider, log, nextPrompts, nextSkills, registry);
        VisionController nextVision =
                new VisionController(this, next.vision(), folder, nextTextures, Clock.systemUTC());
        if (features == null) {
            features = new FeatureService(next.features(), id -> getServer().getPlayer(id), Clock.systemUTC());
        }
        if (memory == null) {
            memory = new SpatialMemory(
                    folder.resolve("memory"),
                    next.perception().maxPlayers(),
                    message -> getLogger().warning(message));
        }
        PerceptionController nextPerception = new PerceptionController(this, next.perception(), memory, recipes);
        nextPerception.attach(nextVision);
        try {
            saveAtomically(candidate, path);
        } catch (IOException e) {
            nextPerception.close();
            nextVision.close();
            replacement.close();
            if (nextTextures != previousTextures) {
                nextTextures.close();
            }
            throw new LocalizedIOException("config.save_failed");
        }
        AssistantService previous = service;
        VisionController previousVision = vision;
        PerceptionController previousPerception = perception;
        memory.setLimit(next.perception().maxPlayers());
        memory.configure(next.memory());
        Messages.install(messages);
        service = replacement;
        this.recipes = recipes;
        this.knowledge = new KnowledgeBase(knowledge);
        this.integrations = detected;
        features.update(next.features());
        vision = nextVision;
        perception = nextPerception;
        textures = nextTextures;
        settings = next;
        triggerPattern = Pattern.compile(
                "^\\s*@?" + Pattern.quote(next.trigger()) + "(?:\\s+|[,:：，]\\s*|$)(.*)$",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        if (previousPerception != null) {
            previousPerception.close();
        }
        if (previousVision != null) {
            previousVision.close();
        }
        if (previous != null) {
            previous.close();
        }
        if (previousTextures != null && previousTextures != nextTextures) {
            previousTextures.close();
        }
        if (HttpAiProvider.proxyBasicAuthBlocked(next)) {
            getLogger().warning(Lang.console("log.proxy_basic_blocked"));
        }
    }

    /** 缺少 vanilla-<版本>.zip 时在后台从 Mojang 官方下载，完成后通知视觉模块。服主自备的资源包不会被下载。线程：主线程。 */
    public void downloadMissingAssets() {
        Settings next = settings;
        VisionSettings vision = next.vision();
        List<Path> packs = VisionController.texturePaths(vision, getDataFolder().toPath());
        if (!vision.enabled() || !vision.textured() || !vision.autoDownload()) {
            return;
        }
        for (Path pack : packs) {
            Optional<String> version = VanillaAssets.versionOf(pack);
            if (Files.isReadable(pack) || version.isEmpty() || !downloading.add(pack)) {
                continue;
            }
            getLogger().info(Lang.console("log.assets_downloading", version.get()));
            Thread.ofVirtual().name("pandagpt-assets").start(() -> {
                try (HttpClient client = HttpAiProvider.newClient(next)) {
                    VanillaAssets.Result result = VanillaAssets.download(client, version.get(), pack);
                    getLogger().info(Lang.console("log.assets_ready", result.files(), pack.getFileName()));
                    if (isEnabled()) {
                        getServer().getScheduler().runTask(this, () -> this.vision.refreshAssets());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                    getLogger().warning(Lang.console("log.assets_failed", reason));
                } finally {
                    downloading.remove(pack);
                }
            });
        }
    }

    // v1 → v2：备份原文件，把按消息计数的 history_size 换算为完整问答轮数。
    private void migrateV1(YamlConfiguration candidate, Path path, Path folder) throws IOException {
        Path backup = folder.resolve("config-v1-backup.yml");
        if (!Files.exists(backup)) {
            Files.copy(path, backup);
        }
        if (!candidate.contains("panda.history_rounds")) {
            candidate.set("panda.history_rounds", Math.max(0, candidate.getInt("panda.history_size", 5) / 2));
        }
        candidate.set("panda.history_size", null);
        candidate.set("config_version", 2);
        getLogger().info(Lang.console("log.migrated_v1"));
    }

    // v2 → v3：顶层的 openai/gemini 密钥与地址并入 panda.model_selection.<服务商>，与模型名放在一起。
    private void migrateV2(YamlConfiguration candidate, Path path, Path folder) throws IOException {
        Path backup = folder.resolve("config-v2-backup.yml");
        if (!Files.exists(backup)) {
            Files.copy(path, backup);
        }
        for (String provider : List.of("openai", "gemini")) {
            for (String field : List.of("api_key", "api_url")) {
                String legacy = candidate.getString(provider + "." + field);
                if (legacy != null && !legacy.isBlank()) {
                    candidate.set("panda.model_selection." + provider + "." + field, legacy);
                }
            }
            candidate.set(provider, null);
        }
        candidate.set("config_version", CONFIG_VERSION);
        getLogger().info(Lang.console("log.migrated_v2"));
    }

    private static void saveAtomically(YamlConfiguration configuration, Path path) throws IOException {
        Path temporary = Files.createTempFile(path.getParent(), "config-", ".tmp");
        try {
            configuration.save(temporary.toFile());
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** 以服务器默认语言描述配置错误；不暴露可能包含 URI、请求头或密钥的底层异常信息。 */
    public static String safeConfigurationError(Exception e) {
        if (e instanceof Localized localized) {
            return localized.localized().toString();
        }
        return e.getClass().getSimpleName();
    }

    /** 以接收者语言描述配置错误。 */
    public static String describe(CommandSender sender, Exception e) {
        if (e instanceof Localized localized) {
            return Lang.text(sender, localized.localized());
        }
        return e.getClass().getSimpleName();
    }

    public Settings settings() {
        return settings;
    }

    public Pattern triggerPattern() {
        return triggerPattern;
    }

    public AssistantService service() {
        return service;
    }

    public VisionController vision() {
        return vision;
    }

    public PerceptionController perception() {
        return perception;
    }

    public SpatialMemory memory() {
        return memory;
    }

    public RecipeKnowledge recipes() {
        return recipes;
    }

    public FeatureService features() {
        return features;
    }

    public Integrations.Detected integrations() {
        return integrations;
    }

    public KnowledgeBase knowledge() {
        return knowledge;
    }

    /** 玩家在聊天中对 Panda 说的话；由 Agent 自行决定查看、观察、记录或回答。 */
    public void ask(Player player, String question) {
        service.ask(player, question);
    }

    @Override
    public void onDisable() {
        if (cleanup != null) {
            cleanup.cancel();
        }
        if (visionTick != null) {
            visionTick.cancel();
        }
        if (perception != null) {
            perception.close();
            perception = null;
        }
        if (vision != null) {
            vision.close();
            vision = null;
        }
        if (service != null) {
            service.close();
            service = null;
        }
        if (memory != null) {
            memory.close();
            memory = null;
        }
        if (textures != null) {
            textures.close();
            textures = null;
        }
        if (features != null) {
            features.close(getServer().getOnlinePlayers());
            features = null;
        }
    }
}
