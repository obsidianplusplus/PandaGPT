package cn.panda.pandagpt.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.TestSupport;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.ToolContext;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;

class IntegrationsTest {
    @Test
    void onlyInstalledAndEnabledPluginsAreActivated() {
        Server server = mock(Server.class);
        PluginManager manager = mock(PluginManager.class);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.isPluginEnabled("Vault")).thenReturn(true);
        when(manager.isPluginEnabled("LuckPerms")).thenReturn(true);
        when(manager.isPluginEnabled("Towny")).thenReturn(true);
        List<String> warnings = new ArrayList<>();
        var detected = Integrations.detect(server, Set.of("towny"), warnings::add);
        assertEquals(Set.of("economy", "ranks"), detected.ids());
        assertTrue(detected.failed().isEmpty());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void everyAdapterLoadsAndToolNamesAreUnique() throws Exception {
        var plugin = mock(ChatGPTIntegration.class);
        Set<String> names = new HashSet<>();
        for (var spec : Integrations.SPECS) {
            for (var tool : Integrations.load(spec).tools(plugin)) {
                assertTrue(names.add(tool.name()), "duplicate tool " + tool.name());
                assertEquals("object", tool.parameters().get("type").getAsString());
            }
        }
        assertTrue(names.size() >= 20);
    }

    @Test
    void newSkillsOnlyLoadForVerifiedActiveIntegrationsAndDeclareRegisteredReadTools() throws Exception {
        var ids = Set.of("citizens", "shopkeepers", "quests", "bentobox", "itemsadder", "oraxen", "jobs", "multiverse");
        var active = cn.panda.pandagpt.agent.SkillLibrary.load(null, Set.of(), ids::contains);
        var absent = cn.panda.pandagpt.agent.SkillLibrary.load(null, Set.of(), requirement -> false);
        var plugin = mock(ChatGPTIntegration.class);
        for (var spec : Integrations.SPECS) {
            if (!ids.contains(spec.id())) continue;
            var skill = active.get(spec.id());
            assertNotNull(skill, spec.id());
            assertNull(absent.get(spec.id()), spec.id());
            var tools = Integrations.load(spec).tools(plugin);
            var names = tools.stream()
                    .map(cn.panda.pandagpt.agent.AgentTool::name)
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(names.containsAll(skill.tools()), spec.id());
            assertTrue(tools.stream().allMatch(cn.panda.pandagpt.agent.AgentTool::independentRead), spec.id());
        }
    }

    @Test
    void incompatibleOptionalApiIsSkippedAndDisabledIntegrationNeverValidates() {
        var server = mock(Server.class);
        var manager = mock(PluginManager.class);
        var installed = mock(org.bukkit.plugin.Plugin.class);
        when(server.getPluginManager()).thenReturn(manager);
        when(manager.isPluginEnabled("Quests")).thenReturn(true);
        when(manager.getPlugin("Quests")).thenReturn(installed);
        var spec = Integrations.SPECS.stream()
                .filter(s -> s.id().equals("quests"))
                .findFirst()
                .orElseThrow();
        var validations = new java.util.concurrent.atomic.AtomicInteger();
        Integration incompatible = new Integration() {
            public String id() {
                return "quests";
            }

            public List<cn.panda.pandagpt.agent.AgentTool> tools(ChatGPTIntegration plugin) {
                return List.of();
            }

            public void validate(org.bukkit.plugin.Plugin plugin) throws ReflectiveOperationException {
                validations.incrementAndGet();
                assertSame(installed, plugin);
                throw new NoSuchMethodException("unsupported version");
            }
        };
        try (var detection = mockStatic(Integrations.class, CALLS_REAL_METHODS)) {
            detection.when(() -> Integrations.load(spec)).thenReturn(incompatible);
            var warnings = new ArrayList<String>();
            var result = Integrations.detect(server, Set.of(), warnings::add);
            assertTrue(result.active().isEmpty());
            assertEquals(List.of("quests"), result.failed());
            assertEquals(1, validations.get());
            assertEquals(1, warnings.size());
            Integrations.detect(server, Set.of("quests"), warnings::add);
            assertEquals(1, validations.get());
        }
    }

    @Test
    void integrationIdsCannotFallBackToPluginPresenceAfterValidationFailureOrDisable() {
        assertFalse(Integrations.requirementAvailable("quests", Set.of(), name -> true));
        assertFalse(Integrations.requirementAvailable("bentobox", Set.of(), name -> true));
        assertTrue(Integrations.requirementAvailable("quests", Set.of("quests"), name -> false));
        assertTrue(Integrations.requirementAvailable("SomeOtherPlugin", Set.of(), name -> true));
        assertFalse(Integrations.requirementAvailable("SomeOtherPlugin", Set.of(), name -> false));
    }

    @Test
    void placeholdersAreResolvedForThePlayerButDenylistedOnesAreBlocked() throws Exception {
        var plugin = mock(ChatGPTIntegration.class);
        when(plugin.settings()).thenReturn(TestSupport.settings());
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        var tool = new PlaceholderAdapter().tools(plugin).getFirst();
        try (var papi = mockStatic(PlaceholderAPI.class)) {
            papi.when(() -> PlaceholderAPI.setPlaceholders(eq(player), eq("%vault_eco_balance%")))
                    .thenReturn("1,024");
            papi.when(() -> PlaceholderAPI.setPlaceholders(eq(player), eq("%foo_bar%")))
                    .thenReturn("%foo_bar%");
            JsonObject args = new JsonObject();
            args.addProperty("placeholders", "%vault_eco_balance% %player_ip% %foo_bar%");
            String result = tool.call(
                            new ToolContext(
                                    player, new AgentTurn(player.getUniqueId(), AgentTurn.Kind.CONVERSATION), "zh_CN"),
                            args)
                    .get()
                    .content();
            assertTrue(result.contains("%vault_eco_balance% = 1,024"));
            assertTrue(result.contains("%player_ip% = [blocked"));
            assertTrue(result.contains("%foo_bar% = [not registered]"));
            papi.verify(() -> PlaceholderAPI.setPlaceholders(eq(player), eq("%player_ip%")), never());
        }
    }
}
