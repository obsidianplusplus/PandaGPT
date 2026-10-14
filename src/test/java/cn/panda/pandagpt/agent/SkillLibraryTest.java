package cn.panda.pandagpt.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.tools.*;
import cn.panda.pandagpt.i18n.LocalizedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SkillLibraryTest {
    @TempDir
    Path folder;

    @Test
    void everyBuiltInSkillParsesAndEveryToolItNamesExists() throws Exception {
        var plugin = mock(ChatGPTIntegration.class);
        var registry = new ToolRegistry()
                .register(AssistantTools.create(plugin))
                .register(PlaceTools.create(plugin))
                .register(GoalTools.create(plugin))
                .register(WorldTools.create(plugin))
                .register(VisionTools.create(plugin))
                .register(CommandTools.create(plugin))
                .register(BuildTools.create(plugin))
                .register(NavigationTools.create(plugin))
                .register(ReminderTools.create(plugin))
                .register(JournalTools.create(plugin))
                .register(InspectTools.create(plugin));
        // 外部插件的 API 在测试类路径上，可以实例化全部适配器检查技能与工具的对应关系。
        Set<String> integrationIds = new HashSet<>();
        for (var spec : cn.panda.pandagpt.integration.Integrations.SPECS) {
            var integration = cn.panda.pandagpt.integration.Integrations.load(spec);
            assertEquals(spec.id(), integration.id());
            integrationIds.add(spec.id());
            registry.register(integration.tools(plugin));
        }
        var skills = SkillLibrary.load(null, Set.of());
        for (Skill skill : skills.all()) {
            for (String requirement : skill.requires()) {
                assertTrue(integrationIds.contains(requirement), skill.name() + " requires unknown " + requirement);
            }
        }
        var withoutPlugins = SkillLibrary.load(null, Set.of(), requirement -> false);
        assertNull(withoutPlugins.get("economy"));
        assertNull(withoutPlugins.get("quests-and-jobs"));
        assertNotNull(withoutPlugins.get("building"));
        assertEquals(SkillLibrary.BUILT_IN.size(), skills.all().size());
        Set<String> owned = new HashSet<>();
        for (Skill skill : skills.all()) {
            assertFalse(skill.instructions().isBlank(), skill.name());
            for (String tool : skill.tools()) {
                assertNotNull(registry.get(tool), skill.name() + " names missing tool " + tool);
                assertTrue(owned.add(tool), tool + " belongs to two skills");
            }
        }
        // 每个工具都必须由某个技能启用，否则模型永远看不到它。
        registry.all().forEach(tool -> assertTrue(owned.contains(tool.name()), tool.name() + " has no skill"));
        String catalogue = skills.catalog();
        assertTrue(catalogue.contains("## Skill: panda"));
        assertTrue(catalogue.contains("- vision:"));
        assertFalse(catalogue.contains("## Skill: vision"));
    }

    @Test
    void customSkillsOverrideOrExtendAndDisabledSkillsDisappear() throws Exception {
        Files.writeString(folder.resolve("events.md"), """
                ---
                name: events
                description: 本周服务器活动
                ---
                周六晚上八点在主城举行建筑比赛。
                """);
        Files.writeString(folder.resolve("vision.md"), """
                ---
                name: vision
                description: 自定义视觉
                tools: look
                ---
                只在玩家要求时使用 look。
                """);
        var skills = SkillLibrary.load(folder, Set.of("commands"));
        assertTrue(skills.get("events").instructions().contains("建筑比赛"));
        assertEquals(java.util.List.of("look"), skills.get("vision").tools());
        assertNull(skills.get("commands"));
        assertFalse(skills.enabledTools().contains("propose_command"));
        assertFalse(skills.enabledTools().contains("focus_object"));
    }

    @Test
    void malformedSkillFilesAreRejectedWithTheirName() throws Exception {
        Files.writeString(folder.resolve("broken.md"), "no header");
        var error = assertThrows(LocalizedIOException.class, () -> SkillLibrary.load(folder, Set.of()));
        assertTrue(error.getMessage().contains("broken.md"));
        Files.writeString(folder.resolve("broken.md"), "---\nname: Bad Name\ndescription: x\n---\nbody");
        assertThrows(LocalizedIOException.class, () -> SkillLibrary.load(folder, Set.of()));
    }

    @Test
    void promptsRenderVariablesAndCanBeOverridden() throws Exception {
        var bundled = PromptLibrary.load(null);
        String system =
                bundled.render("system", Map.of("personality", "我是熊猫", "skills", "SKILLS", "language", "ja_JP"));
        assertTrue(system.startsWith("我是熊猫"));
        assertTrue(system.contains("SKILLS"));
        assertTrue(system.contains("ja_JP"));
        assertTrue(system.contains("{{snapshot}}"), "unknown variables stay visible");
        Files.writeString(folder.resolve("proactive.md"), "信号：{{signal}}");
        assertEquals("信号：低血量", PromptLibrary.load(folder).render("proactive", Map.of("signal", "低血量")));
    }
}
