package cn.panda.pandagpt.i18n;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/**
 * 不可变的多语言消息表。
 *
 * <p>内置语言文件位于 JAR 的 {@code lang/<语言>.yml}；服主可在 {@code plugins/PandaGPT/lang/} 下覆盖任意键。
 * 查找顺序：玩家语言 → 服务器默认语言 → 简体中文 → 键名本身。线程：实例不可变；当前实例通过 volatile 字段发布。
 */
public final class Messages {
    /** 最终兜底语言。 */
    public static final String FALLBACK = "zh_CN";

    /** 内置支持的语言，顺序即同语种匹配时的优先顺序。 */
    public static final List<String> SUPPORTED = List.of(
            "zh_CN", "zh_TW", "en_US", "ja_JP", "ko_KR", "es_ES", "fr_FR", "de_DE", "ru_RU", "pt_BR", "ar_SA", "hi_IN");

    // 使用繁体中文的地区代码。
    private static final Set<String> TRADITIONAL_REGIONS = Set.of("TW", "HK", "MO");

    private static volatile Messages active;

    private final Map<String, Map<String, String>> tables;
    private final String defaultLanguage;
    private final boolean perPlayer;

    private Messages(Map<String, Map<String, String>> tables, String defaultLanguage, boolean perPlayer) {
        this.tables = Map.copyOf(tables);
        this.defaultLanguage = defaultLanguage;
        this.perPlayer = perPlayer;
    }

    /** 当前生效的消息表；首次访问时加载内置简体中文默认值。 */
    public static Messages active() {
        Messages current = active;
        if (current == null) {
            synchronized (Messages.class) {
                if (active == null) {
                    try {
                        active = load(null, FALLBACK, true);
                    } catch (IOException e) {
                        throw new IllegalStateException("内置语言文件缺失或损坏", e);
                    }
                }
                current = active;
            }
        }
        return current;
    }

    /** 发布新的消息表，仅在配置整体校验通过后调用。 */
    public static void install(Messages messages) {
        active = messages;
    }

    /**
     * 加载内置语言文件，并用数据目录中的同名文件覆盖。
     *
     * @param dataFolder 插件数据目录；为 null 时只读取内置文件
     */
    public static Messages load(Path dataFolder, String defaultLanguage, boolean perPlayer) throws IOException {
        if (!SUPPORTED.contains(defaultLanguage)) {
            throw new LocalizedException("config.language_unsupported", defaultLanguage, String.join(", ", SUPPORTED));
        }
        Map<String, Map<String, String>> tables = new HashMap<>();
        for (String language : SUPPORTED) {
            Map<String, String> table = new HashMap<>();
            try (InputStream bundled =
                    Messages.class.getClassLoader().getResourceAsStream("lang/" + language + ".yml")) {
                if (bundled == null) {
                    throw new IOException("Missing bundled language file: " + language);
                }
                read(new InputStreamReader(bundled, StandardCharsets.UTF_8), table, "lang/" + language + ".yml");
            }
            if (dataFolder != null) {
                Path custom = dataFolder.resolve("lang").resolve(language + ".yml");
                if (Files.isRegularFile(custom)) {
                    try (Reader reader = Files.newBufferedReader(custom, StandardCharsets.UTF_8)) {
                        read(reader, table, "lang/" + language + ".yml");
                    }
                }
            }
            tables.put(language, table);
        }
        return new Messages(tables, defaultLanguage, perPlayer);
    }

    private static void read(Reader reader, Map<String, String> into, String name) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(reader);
        } catch (InvalidConfigurationException e) {
            throw new LocalizedIOException("config.language_invalid", name);
        }
        for (String key : yaml.getKeys(true)) {
            if (!yaml.isConfigurationSection(key)) {
                into.put(key, yaml.getString(key, ""));
            }
        }
    }

    public String defaultLanguage() {
        return defaultLanguage;
    }

    /** 指定语言包含的全部键，供测试比较各语言是否完整。 */
    public Map<String, String> table(String language) {
        return tables.getOrDefault(language, Map.of());
    }

    /** 接收者应使用的语言；控制台与关闭按玩家语言时使用服务器默认语言。 */
    public String language(CommandSender sender) {
        if (perPlayer && sender instanceof Player player) {
            return resolve(player.locale());
        }
        return defaultLanguage;
    }

    /** 把客户端语言映射到最接近的内置语言。 */
    public String resolve(Locale locale) {
        if (locale == null || locale.getLanguage().isEmpty()) {
            return defaultLanguage;
        }
        String language = locale.getLanguage().toLowerCase(Locale.ROOT);
        String country = locale.getCountry().toUpperCase(Locale.ROOT);
        if (language.equals("zh")) {
            boolean traditional = "Hant".equals(locale.getScript()) || TRADITIONAL_REGIONS.contains(country);
            return traditional ? "zh_TW" : "zh_CN";
        }
        String exact = language + "_" + country;
        if (SUPPORTED.contains(exact)) {
            return exact;
        }
        for (String candidate : SUPPORTED) {
            if (candidate.startsWith(language + "_")) {
                return candidate;
            }
        }
        return defaultLanguage;
    }

    /** 按语言渲染消息，缺失的键依次回退到默认语言、简体中文与键名。 */
    public String format(String language, Msg message) {
        String pattern = lookup(language, message.key());
        return substitute(language, pattern, message.args());
    }

    public String format(String language, String key, Object... args) {
        return format(language, Msg.of(key, args));
    }

    private String lookup(String language, String key) {
        String value = table(language).get(key);
        if (value == null) {
            value = table(defaultLanguage).get(key);
        }
        if (value == null) {
            value = table(FALLBACK).get(key);
        }
        return value == null ? key : value;
    }

    // 只识别 {数字} 占位符；不使用 MessageFormat，避免法语等语言中的撇号被当作转义符。
    private String substitute(String language, String pattern, List<Object> args) {
        if (args.isEmpty() || pattern.indexOf('{') < 0) {
            return pattern;
        }
        StringBuilder out = new StringBuilder(pattern.length() + 16);
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            int close = c == '{' ? pattern.indexOf('}', i + 1) : -1;
            if (close > i + 1 && isDigits(pattern, i + 1, close)) {
                int index = Integer.parseInt(pattern.substring(i + 1, close));
                if (index < args.size()) {
                    Object arg = args.get(index);
                    out.append(arg instanceof Msg nested ? format(language, nested) : String.valueOf(arg));
                    i = close + 1;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private static boolean isDigits(String text, int from, int to) {
        if (to - from > 2) {
            return false;
        }
        for (int i = from; i < to; i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
