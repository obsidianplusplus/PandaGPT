package cn.panda.pandagpt.agent;

import cn.panda.pandagpt.i18n.LocalizedIOException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 提示词模板库。模板是 Markdown 文件，使用 {{变量}} 占位；服主可在 prompts/ 中覆盖。
 *
 * <p>未知变量保持原样，便于发现拼写错误。线程：实例不可变。
 */
public final class PromptLibrary {
    /** 内置模板。 */
    public static final List<String> BUILT_IN = List.of("system", "proactive", "runtime");

    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-z_]+)}}");
    private static final int MAX_BYTES = 32768;

    private final Map<String, String> templates;

    private PromptLibrary(Map<String, String> templates) {
        this.templates = Map.copyOf(templates);
    }

    public static PromptLibrary load(Path folder) throws IOException {
        Map<String, String> templates = new HashMap<>();
        for (String name : BUILT_IN) {
            Path custom = folder == null ? null : folder.resolve(name + ".md");
            if (custom != null && Files.isRegularFile(custom)) {
                if (Files.size(custom) > MAX_BYTES) {
                    throw new LocalizedIOException("config.prompt_invalid", name + ".md");
                }
                templates.put(name, Files.readString(custom, StandardCharsets.UTF_8));
                continue;
            }
            try (InputStream in = PromptLibrary.class.getClassLoader().getResourceAsStream("prompts/" + name + ".md")) {
                if (in == null) {
                    throw new IOException("Missing bundled prompt: " + name);
                }
                templates.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return new PromptLibrary(templates);
    }

    /** 渲染模板，把 {{name}} 替换为对应变量。 */
    public String render(String name, Map<String, String> variables) {
        String template = templates.get(name);
        if (template == null) {
            throw new IllegalArgumentException("Unknown prompt " + name);
        }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = variables.get(matcher.group(1));
            matcher.appendReplacement(out, Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(out);
        return out.toString().strip();
    }
}
