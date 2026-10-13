package cn.panda.pandagpt.config;

import org.bukkit.configuration.file.FileConfiguration;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import cn.panda.pandagpt.vision.VisionSettings;

/** Immutable configuration: workers never read Bukkit configuration. */
public record Settings(String provider, String apiKey, String apiUrl, String model, String personality,
                       String trigger, boolean cancelChat, long cooldownMillis, int historyRounds,
                       int maxSessions, Duration sessionTtl, int maxPromptChars, int contextBudget,
                       int maxOutputTokens, int workers, int queueSize, Duration connectTimeout,
                       Duration requestTimeout, int maxResponseBytes, boolean environmentEnabled,
                       int radius, int maxEntities, int maxRecipes, boolean commandAwareness,
                       boolean allowExecution, int maxActions, Duration confirmationTtl,
                       Set<String> allowedCommands, Set<String> blockedCommands, Proxy proxy,
                       boolean loggingEnabled, boolean logConversations, int logBytes, int logFiles, VisionSettings vision) {
    public record Proxy(boolean enabled, String host, int port, String username, String password) {
        @Override public String toString() { return "Proxy[enabled=" + enabled + "]"; }
    }

    public static Settings load(FileConfiguration c) {
        String provider = text(c, "panda.model_selection.provider", "gemini").toLowerCase(Locale.ROOT);
        if (!Set.of("openai", "gemini").contains(provider))
            throw new IllegalArgumentException("panda.model_selection.provider must be openai or gemini");
        String key = c.getString(provider + ".api_key", "").trim();
        String environmentKey = System.getenv("PANDAGPT_" + provider.toUpperCase(Locale.ROOT) + "_API_KEY");
        if (environmentKey != null && !environmentKey.isBlank()) key = environmentKey.trim();
        String url = text(c, provider + ".api_url", provider.equals("openai")
                ? "https://api.openai.com/v1/chat/completions"
                : "https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent");
        url = url.replace("?key=%s", ""); // Migrate the v1 Gemini URL; key goes in a header.
        URI uri;
        try { uri = URI.create(url.replace("%s", "model")); }
        catch (RuntimeException e) { throw new IllegalArgumentException(provider + ".api_url is invalid"); }
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                || uri.getRawQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException(provider + ".api_url must be HTTPS without credentials, query or fragment");
        if (!"player".equalsIgnoreCase(c.getString("panda.command_awareness.executor", "player")))
            throw new IllegalArgumentException("Only command_awareness.executor: player is supported");
        String model = text(c, "panda.model_selection." + provider + ".model", "");
        if (!model.matches("[A-Za-z0-9._:/-]+")) throw new IllegalArgumentException("Invalid model name");
        Proxy proxy = new Proxy(c.getBoolean("panda.proxy.enabled", false),
                c.getString("panda.proxy.host", "localhost"), number(c, "panda.proxy.port", 10808, 1, 65535),
                c.getString("panda.proxy.username", ""), c.getString("panda.proxy.password", ""));
        if (proxy.enabled && proxy.host.isBlank()) throw new IllegalArgumentException("Proxy host is empty");
        String trigger = text(c, "panda.trigger_word", "panda");
        if (trigger.contains(" ") || trigger.startsWith("@"))
            throw new IllegalArgumentException("trigger_word must be one word without @");
        return new Settings(provider, key, url, model, text(c, "panda.personality", "You are Panda, a Minecraft assistant."),
                trigger, c.getBoolean("panda.cancel_chat_message", true),
                number(c, "panda.cooldown", 5000, 0, 3600000), number(c, "panda.history_rounds", 5, 0, 50),
                number(c, "panda.max_sessions", 1000, 1, 100000), seconds(c, "panda.session_ttl_seconds", 1800, 10, 86400),
                number(c, "panda.max_prompt_chars", 2000, 1, 16000), number(c, "panda.context_char_budget", 16000, 2000, 128000),
                number(c, "panda.max_output_tokens", 1000, 1, 8192), number(c, "panda.requests.workers", 4, 1, 32),
                number(c, "panda.requests.queue_size", 32, 1, 1000), seconds(c, "panda.requests.connect_timeout_seconds", 10, 1, 60),
                seconds(c, "panda.requests.timeout_seconds", 45, 1, 180), number(c, "panda.requests.max_response_bytes", 1048576, 1024, 4194304),
                c.getBoolean("panda.environment_awareness.enabled", true), number(c, "panda.environment_awareness.radius", 20, 1, 48),
                number(c, "panda.environment_awareness.max_entities", 8, 0, 32), number(c, "panda.environment_awareness.max_recipes", 6, 0, 16),
                c.getBoolean("panda.command_awareness.enabled", true), c.getBoolean("panda.command_awareness.allow_execution", false),
                number(c, "panda.command_awareness.max_executions", 3, 1, 5), seconds(c, "panda.command_awareness.confirmation_ttl_seconds", 60, 5, 300),
                roots(c, "panda.command_awareness.allowed_commands"), roots(c, "panda.command_awareness.blocked_commands"), proxy,
                c.getBoolean("panda.logging.enabled", true), c.getBoolean("panda.logging.conversations", false),
                number(c, "panda.logging.max_bytes", 1048576, 1024, 16777216), number(c, "panda.logging.files", 3, 1, 20),
                VisionSettings.load(c));
    }
    private static int number(FileConfiguration c, String path, int fallback, int min, int max) {
        Object value = c.get(path);
        if (value != null && !(value instanceof Number)) throw new IllegalArgumentException(path + " must be a number");
        long n = c.getLong(path, fallback);
        if (n < min || n > max) throw new IllegalArgumentException(path + " must be between " + min + " and " + max);
        return (int) n;
    }
    private static Duration seconds(FileConfiguration c, String path, int fallback, int min, int max) {
        return Duration.ofSeconds(number(c, path, fallback, min, max));
    }
    private static String text(FileConfiguration c, String path, String fallback) {
        String value = c.getString(path, fallback);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(path + " is empty");
        return value.trim();
    }
    private static Set<String> roots(FileConfiguration c, String path) {
        return c.getStringList(path).stream().map(s -> s.toLowerCase(Locale.ROOT)).peek(s -> {
            if (!s.matches("[a-z0-9_-]+")) throw new IllegalArgumentException(path + " requires unqualified command roots");
        }).collect(Collectors.toUnmodifiableSet());
    }
    @Override public String toString() { return "Settings[provider=" + provider + ", model=" + model + "]"; }
}
