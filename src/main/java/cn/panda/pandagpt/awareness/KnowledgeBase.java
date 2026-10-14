package cn.panda.pandagpt.awareness;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 服务器资料（knowledge.md）的检索。按标题与空行切分为段落，用词项与中日韩字符二元组的重合度打分。
 *
 * <p>Agent 按需检索，而不是每一步都把整份资料塞进提示词。线程：实例不可变，可跨线程使用。
 */
public final class KnowledgeBase {
    /** 一个段落及其所属标题。 */
    public record Section(String heading, String text) {}

    private final List<Section> sections;

    public KnowledgeBase(String markdown) {
        List<Section> parsed = new ArrayList<>();
        String heading = "";
        StringBuilder current = new StringBuilder();
        for (String line : markdown.replace("\r\n", "\n").split("\n")) {
            boolean title = line.startsWith("#");
            if (title || line.isBlank()) {
                add(parsed, heading, current);
                if (title) {
                    heading = line.replaceFirst("^#+\\s*", "").strip();
                }
                continue;
            }
            current.append(line).append('\n');
        }
        add(parsed, heading, current);
        sections = List.copyOf(parsed);
    }

    private static void add(List<Section> into, String heading, StringBuilder text) {
        if (!text.toString().isBlank()) {
            into.add(new Section(heading, text.toString().strip()));
        }
        text.setLength(0);
    }

    /** 全部标题，作为系统提示中的资料目录。 */
    public List<String> outline() {
        return sections.stream()
                .map(Section::heading)
                .filter(h -> !h.isEmpty())
                .distinct()
                .toList();
    }

    /** 返回与查询最相关的若干段落，总长度不超过 maxChars。 */
    public List<Section> search(String query, int limit, int maxChars) {
        Set<String> terms = terms(query);
        if (terms.isEmpty()) {
            return List.of();
        }
        record Scored(Section section, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (Section section : sections) {
            Set<String> words = terms(section.heading() + " " + section.text());
            long hits = terms.stream().filter(words::contains).count();
            if (hits > 0) {
                // 标题命中加权；用段落长度轻微归一化，避免长段落占优。
                long headingHits = terms.stream()
                        .filter(terms(section.heading())::contains)
                        .count();
                scored.add(new Scored(section, hits + headingHits * 0.5 - Math.log1p(words.size()) * 0.05));
            }
        }
        scored.sort(Comparator.comparingDouble(Scored::score).reversed());
        List<Section> result = new ArrayList<>();
        int used = 0;
        for (Scored s : scored) {
            if (result.size() >= limit || used + s.section().text().length() > maxChars) {
                break;
            }
            result.add(s.section());
            used += s.section().text().length();
        }
        return result;
    }

    // 拉丁文字按单词切分；中日韩文字取相邻两字组成的二元组。
    static Set<String> terms(String text) {
        Set<String> terms = new HashSet<>();
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : lower.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() >= 2 && word.chars().noneMatch(KnowledgeBase::cjk)) {
                terms.add(word);
            }
        }
        int[] cps = lower.codePoints().toArray();
        for (int i = 0; i + 1 < cps.length; i++) {
            if (cjk(cps[i]) && cjk(cps[i + 1])) {
                terms.add(new String(cps, i, 2));
            }
        }
        return terms;
    }

    private static boolean cjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
