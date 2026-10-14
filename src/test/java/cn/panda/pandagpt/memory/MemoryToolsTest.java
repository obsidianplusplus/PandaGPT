package cn.panda.pandagpt.memory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.ToolContext;
import cn.panda.pandagpt.agent.tools.MemoryTools;
import cn.panda.pandagpt.perception.SpatialMemory;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MemoryToolsTest {
    @TempDir
    Path folder;

    @Test
    void acceptsPlayerQuoteButRejectsAssistantInferenceAndProactiveWrites() throws Exception {
        UUID id = UUID.randomUUID();
        var plugin = mock(ChatGPTIntegration.class);
        var player = mock(Player.class);
        var world = mock(World.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getWorld()).thenReturn(world);
        when(world.getUID()).thenReturn(UUID.randomUUID());
        try (var memory = new SpatialMemory(folder, 4, s -> fail(s))) {
            when(plugin.memory()).thenReturn(memory);
            var tool = MemoryTools.create(plugin).getFirst();
            var args = JsonParser.parseString(
                            "{\"kind\":\"fact\",\"topic\":\"建筑风格\",\"text\":\"喜欢中式建筑\",\"scope\":\"global\"}")
                    .getAsJsonObject();
            var context =
                    new ToolContext(player, new AgentTurn(id, AgentTurn.Kind.CONVERSATION, "以后记住我喜欢中式建筑"), "zh_CN");
            assertTrue(
                    tool.call(context, args).get(5, TimeUnit.SECONDS).content().contains("persisted"));
            args.addProperty("text", "害怕下界");
            assertTrue(
                    tool.call(context, args).get(5, TimeUnit.SECONDS).content().startsWith("ERROR:"));
            args.addProperty("text", "喜欢中式建筑");
            var proactive = new ToolContext(player, new AgentTurn(id, AgentTurn.Kind.PROACTIVE, "喜欢中式建筑"), "zh_CN");
            assertTrue(tool.call(proactive, args)
                    .get(5, TimeUnit.SECONDS)
                    .content()
                    .startsWith("ERROR:"));
            assertEquals(1, memory.view(id).game().events().size());
        }
    }
}
