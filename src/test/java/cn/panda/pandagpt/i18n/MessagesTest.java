package cn.panda.pandagpt.i18n;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MessagesTest {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\d+}");

    @TempDir
    Path folder;

    @Test
    void everyBundledLanguageHasTheSameKeysAndPlaceholdersAsSimplifiedChinese() throws Exception {
        Messages messages = Messages.load(null, Messages.FALLBACK, true);
        var reference = messages.table(Messages.FALLBACK);
        assertTrue(reference.size() > 150);
        for (String language : Messages.SUPPORTED) {
            var table = messages.table(language);
            assertEquals(reference.keySet(), table.keySet(), language);
            for (var entry : reference.entrySet()) {
                assertEquals(
                        placeholders(entry.getValue()),
                        placeholders(table.get(entry.getKey())),
                        language + " " + entry.getKey());
                assertFalse(table.get(entry.getKey()).isBlank(), language + " " + entry.getKey());
            }
        }
    }

    @Test
    void clientLocalesMapToTheClosestBundledLanguage() throws Exception {
        Messages messages = Messages.load(null, "en_US", true);
        assertEquals("zh_CN", messages.resolve(Locale.forLanguageTag("zh-CN")));
        assertEquals("zh_TW", messages.resolve(Locale.forLanguageTag("zh-HK")));
        assertEquals("zh_TW", messages.resolve(Locale.forLanguageTag("zh-Hant")));
        assertEquals("pt_BR", messages.resolve(Locale.forLanguageTag("pt-PT")));
        assertEquals("es_ES", messages.resolve(Locale.forLanguageTag("es-MX")));
        assertEquals("en_US", messages.resolve(Locale.forLanguageTag("en-GB")));
        assertEquals("en_US", messages.resolve(Locale.forLanguageTag("sv-SE")));
        assertEquals("en_US", messages.resolve(null));
    }

    @Test
    void placeholdersAcceptNestedMessagesAndApostrophesAreLiteral() throws Exception {
        Messages messages = Messages.load(null, "fr_FR", true);
        String text = messages.format("fr_FR", Msg.of("suggest.local_risk", Msg.of("route.DROP")));
        assertTrue(text.contains("dénivelé"), text);
        assertTrue(messages.format("fr_FR", "service.no_permission").contains("n'avez"));
        // 缺失的键回退为键名本身，不会抛出异常。
        assertEquals("missing.key", messages.format("fr_FR", "missing.key"));
    }

    @Test
    void dataFolderOverridesSingleKeysAndUnsupportedDefaultIsRejected() throws Exception {
        Files.createDirectories(folder.resolve("lang"));
        Files.writeString(folder.resolve("lang/zh_CN.yml"), "service:\n  thinking: \"稍等…\"\n");
        Messages messages = Messages.load(folder, "zh_CN", false);
        assertEquals("稍等…", messages.format("zh_CN", "service.thinking"));
        assertEquals("请输入问题。", messages.format("zh_CN", "service.empty_question"));
        assertThrows(LocalizedException.class, () -> Messages.load(folder, "xx_XX", true));
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group());
        }
        return found;
    }
}
