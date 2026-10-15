package cn.panda.pandagpt.agent;

import cn.panda.pandagpt.i18n.LocalizedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 技能库：加载内置技能与服主自定义技能。
 *
 * <p>技能文件是带 YAML 风格头部的 Markdown：
 *
 * <pre>
 * ---
 * name: places
 * description: 一句话说明何时使用
 * tools: remember_place, list_places
 * always: false
 * ---
 * 正文：给模型的详细说明……
 * </pre>
 *
 * <p>数据目录 skills/ 中的同名文件覆盖内置技能，新文件名即新增技能（可以只有说明、不带工具）。线程：实例不可变。
 */
public final class SkillLibrary {
    /** 内置技能，对应 JAR 中的 skills/&lt;名称&gt;.md。 */
    public static final List<String> BUILT_IN = List.of(
            "panda",
            "auraskills",
            "bentobox",
            "citizens",
            "itemsadder",
            "jobs",
            "multiverse",
            "oraxen",
            "quests",
            "shopkeepers",
            "block-history",
            "building",
            "claims",
            "combat",
            "commands",
            "crafting",
            "economy",
            "enchanting",
            "essentials",
            "exploration",
            "farming",
            "goals",
            "items",
            "journal",
            "lands",
            "mcmmo",
            "game-memory",
            "navigation",
            "new-player",
            "notes",
            "placeholders",
            "places",
            "quests-and-jobs",
            "ranks",
            "redstone",
            "regions",
            "reminders",
            "server-guide",
            "survival",
            "teleport",
            "towny",
            "trading",
            "vision");

    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9-]{0,31}");
    private static final int MAX_SKILLS = 64;
    private static final int MAX_BYTES = 16384;

    private final Map<String, Skill> skills;

    private SkillLibrary(Map<String, Skill> skills) {
        this.skills = Map.copyOf(skills);
    }

    /**
     * @param folder 数据目录下的 skills 文件夹，可为 null
     * @param disabled 服主在配置中关闭的技能
     */
    public static SkillLibrary load(Path folder, Set<String> disabled) throws IOException {
        return load(folder, disabled, requirement -> true);
    }

    /**
     * @param available 判断技能头部 requires 中的集成编号或插件名是否可用；不可用的技能不加载
     */
    public static SkillLibrary load(Path folder, Set<String> disabled, java.util.function.Predicate<String> available)
            throws IOException {
        Map<String, Skill> skills = new LinkedHashMap<>();
        for (String name : BUILT_IN) {
            try (InputStream in = SkillLibrary.class.getClassLoader().getResourceAsStream("skills/" + name + ".md")) {
                if (in == null) {
                    throw new IOException("Missing bundled skill: " + name);
                }
                Skill skill = parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), name + ".md");
                skills.put(skill.name(), skill);
            }
        }
        if (folder != null && Files.isDirectory(folder)) {
            try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.md")) {
                for (Path file : files) {
                    if (Files.size(file) > MAX_BYTES) {
                        throw new LocalizedIOException("config.skill_invalid", file.getFileName());
                    }
                    Skill skill = parse(
                            Files.readString(file, StandardCharsets.UTF_8),
                            file.getFileName().toString());
                    skills.put(skill.name(), skill);
                }
            }
        }
        disabled.forEach(skills::remove);
        skills.values()
                .removeIf(skill -> !skill.requires().isEmpty()
                        && !skill.requires().stream().allMatch(available));
        if (skills.size() > MAX_SKILLS) {
            throw new LocalizedIOException("config.skill_invalid", "skills/");
        }
        return new SkillLibrary(skills);
    }

    /** 解析一个技能文件；头部缺失或字段非法时报告文件名。 */
    static Skill parse(String text, String file) throws IOException {
        String normalized = text.replace("\r\n", "\n");
        if (!normalized.startsWith("---\n")) {
            throw new LocalizedIOException("config.skill_invalid", file);
        }
        int end = normalized.indexOf("\n---", 4);
        if (end < 0) {
            throw new LocalizedIOException("config.skill_invalid", file);
        }
        Map<String, String> header = new LinkedHashMap<>();
        for (String line : normalized.substring(4, end).split("\n")) {
            int colon = line.indexOf(':');
            if (colon > 0) {
                header.put(
                        line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                        line.substring(colon + 1).strip());
            }
        }
        String name = header.getOrDefault("name", "");
        String description = header.getOrDefault("description", "");
        if (!NAME.matcher(name).matches() || description.isBlank() || description.length() > 300) {
            throw new LocalizedIOException("config.skill_invalid", file);
        }
        List<String> tools = new ArrayList<>();
        String list = header.getOrDefault("tools", "").replace("[", "").replace("]", "");
        Arrays.stream(list.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .forEach(tools::add);
        String body =
                normalized.substring(normalized.indexOf('\n', end + 1) + 1).strip();
        List<String> requires = Arrays.stream(
                        header.getOrDefault("requires", "").split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
        return new Skill(
                name, description, body, tools, Boolean.parseBoolean(header.getOrDefault("always", "false")), requires);
    }

    public Skill get(String name) {
        return skills.get(name);
    }

    public Collection<Skill> all() {
        return skills.values();
    }

    /** 已启用技能声明的全部工具名。 */
    public Set<String> enabledTools() {
        Set<String> tools = new java.util.LinkedHashSet<>();
        skills.values().forEach(skill -> tools.addAll(skill.tools()));
        return tools;
    }

    /** 系统提示中的技能目录：常驻技能放全文，其余只放名称与简介。 */
    public String catalog() {
        StringBuilder always = new StringBuilder();
        StringBuilder optional = new StringBuilder();
        for (Skill skill : skills.values()) {
            if (skill.always()) {
                always.append("\n## Skill: ")
                        .append(skill.name())
                        .append('\n')
                        .append(skill.instructions())
                        .append('\n');
            } else {
                optional.append("- ")
                        .append(skill.name())
                        .append(": ")
                        .append(skill.description())
                        .append('\n');
            }
        }
        return always
                + (optional.isEmpty()
                        ? ""
                        : "\nLoadable skills (call load_skill with the name to read full instructions before relying on "
                                + "them):\n" + optional);
    }
}
