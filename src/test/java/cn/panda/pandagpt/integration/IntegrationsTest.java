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
