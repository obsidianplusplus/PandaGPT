package cn.panda.pandagpt.integration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.ChatGPTIntegration;
import cn.panda.pandagpt.agent.AgentTool;
import cn.panda.pandagpt.agent.AgentTurn;
import cn.panda.pandagpt.agent.ToolContext;
import com.google.gson.JsonObject;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

class QuestsAdapterTest {
    private final UUID caller = UUID.randomUUID();
    private final NativeQuests nativePlugin = mock(NativeQuests.class);
    private final Player player = mock(Player.class);
    private final NativeQuester quester = mock(NativeQuester.class);

    private AgentTool tool() throws Exception {
        when(nativePlugin.isEnabled()).thenReturn(true);
        when(player.getUniqueId()).thenReturn(caller);
        when(player.hasPermission("pandagpt.use")).thenReturn(true);
        when(player.hasPermission("quests.quest")).thenReturn(true);
        when(quester.getUUID()).thenReturn(caller);
        when(nativePlugin.getOfflineQuesters()).thenReturn(List.of(quester));
        var api = new QuestsAdapter.Api(
                NativeQuests.class,
                NativeQuester.class,
                NativeQuester.class,
                NativeQuest.class,
                NativeStage.class,
                NativeProgress.class,
                NativeBlockStack.class,
                NativeCustomObjective.class);
        return new QuestsAdapter(nativePlugin, api)
                .tools(mock(ChatGPTIntegration.class))
                .getFirst();
    }

    private String call(AgentTool tool) throws Exception {
        return tool.call(
                        new ToolContext(player, new AgentTurn(caller, AgentTurn.Kind.CONVERSATION), "zh_CN"),
                        new JsonObject())
                .get()
                .content();
    }

    @Test
    void readsOnlyCallerAndExistingNativeCountersWithoutFormattingOrInitializingData() throws Exception {
        AgentTool tool = tool();
        NativeQuester other = mock(NativeQuester.class);
        when(other.getUUID()).thenReturn(UUID.randomUUID());
        when(nativePlugin.getOfflineQuesters()).thenReturn(List.of(other, quester));
        NativeQuest quest = mock(NativeQuest.class);
        NativeStage stage = mock(NativeStage.class);
        NativeProgress progress = mock(NativeProgress.class);
        NativeBlockStack block = mock(NativeBlockStack.class);
        when(quest.getName()).thenReturn("§aMine iron");
        when(quest.getId()).thenReturn("mine_iron");
        when(quester.getCurrentQuests()).thenReturn(Map.of(quest, 1));
        when(quester.getQuestProgress()).thenReturn(Map.of(quest, progress));
        when(quester.getCurrentStage(quest)).thenReturn(stage);
        doReturn(List.of(block)).when(stage).getBlocksToBreak();
        when(block.getType()).thenReturn(Material.IRON_ORE);
        when(block.getAmount()).thenReturn(8);
        doReturn(List.of(3)).when(progress).getBlocksBroken();
        ItemStack craftingTable = mock(ItemStack.class);
        when(craftingTable.getType()).thenReturn(Material.CRAFTING_TABLE);
        when(craftingTable.getAmount()).thenReturn(1);
        doReturn(List.of(craftingTable)).when(stage).getItemsToCraft();
        when(stage.getFishToCatch()).thenReturn(6);
        when(progress.getFishCaught()).thenReturn(2);
        String result = call(tool);
        assertTrue(tool.independentRead());
        assertTrue(result.contains("Mine iron [mine_iron], stage: 2"), result);
        assertTrue(result.contains("break IRON_ORE: 3/8"), result);
        assertTrue(result.contains("craft CRAFTING_TABLE: unknown/1"), result);
        assertTrue(result.contains("catch fish: 2/6"), result);
        verify(other, never()).getCurrentQuests();
        verify(other, never()).getQuestProgress();
        verify(nativePlugin, never()).getQuester(any());
        verify(quester, never()).getCurrentObjectives(any(), anyBoolean(), anyBoolean());
        verify(quester, never()).getQuestProgressOrDefault(any());
    }

    @Test
    void missingProgressDoesNotCreateDefaults() throws Exception {
        AgentTool tool = tool();
        NativeQuest quest = mock(NativeQuest.class);
        when(quest.getName()).thenReturn("Quest");
        when(quest.getId()).thenReturn("q");
        when(quester.getCurrentQuests()).thenReturn(Map.of(quest, 0));
        when(quester.getQuestProgress()).thenReturn(Map.of());
        assertTrue(call(tool).contains("Progress has not loaded"));
        verify(quester, never()).getQuestProgressOrDefault(any());
        verify(quester, never()).getCurrentObjectives(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void permissionAndPluginEnablementAreRecheckedAtCallTime() throws Exception {
        AgentTool tool = tool();
        assertTrue(tool.available(player));
        when(player.hasPermission("quests.quest")).thenReturn(false);
        assertFalse(tool.available(player));
        assertTrue(call(tool).startsWith("ERROR:"));
        verify(nativePlugin, never()).getOfflineQuesters();
        when(player.hasPermission("quests.quest")).thenReturn(true);
        when(nativePlugin.isEnabled()).thenReturn(false);
        assertFalse(tool.available(player));
        assertTrue(call(tool).startsWith("ERROR:"));
    }

    @Test
    void loadingAndBoundedRecordSearchDoNotCreateQuesters() throws Exception {
        AgentTool tool = tool();
        when(nativePlugin.isLoading()).thenReturn(true);
        assertTrue(call(tool).contains("still loading"));
        verify(nativePlugin, never()).getOfflineQuesters();
        when(nativePlugin.isLoading()).thenReturn(false);
        NativeQuester other = mock(NativeQuester.class);
        when(other.getUUID()).thenReturn(UUID.randomUUID());
        var records = new java.util.ArrayList<NativeQuester>(Collections.nCopies(QuestsAdapter.MAX_QUESTERS, other));
        records.add(quester);
        when(nativePlugin.getOfflineQuesters()).thenReturn(records);
        assertTrue(call(tool).contains("bounded scan limit"));
        verify(other, times(QuestsAdapter.MAX_QUESTERS)).getUUID();
        verify(quester, never()).getCurrentQuests();
        verify(nativePlugin, never()).getQuester(any());
    }

    @Test
    void objectiveOutputHasHardLimitsAndUnknownCountersStayUnknown() throws Exception {
        AgentTool tool = tool();
        NativeQuest quest = mock(NativeQuest.class);
        NativeStage stage = mock(NativeStage.class);
        NativeProgress progress = mock(NativeProgress.class);
        when(quest.getName()).thenReturn("q");
        when(quest.getId()).thenReturn("q");
        when(quester.getCurrentQuests()).thenReturn(Map.of(quest, 0));
        when(quester.getQuestProgress()).thenReturn(Map.of(quest, progress));
        when(quester.getCurrentStage(quest)).thenReturn(stage);
        doReturn(Collections.nCopies(1000, "Display " + "x".repeat(3000)))
                .when(stage)
                .getPasswordDisplays();
        String result = call(tool);
        assertTrue(result.contains("unknown/1"));
        assertTrue(result.contains("More objectives omitted"));
        assertTrue(result.length() <= QuestsAdapter.MAX_OUTPUT, "result chars: " + result.length());
    }

    @Test
    void missingNativeMethodsFailValidation() {
        assertThrows(
                NoSuchMethodException.class,
                () -> new QuestsAdapter.Api(
                        Plugin.class,
                        NativeQuester.class,
                        NativeQuester.class,
                        NativeQuest.class,
                        NativeStage.class,
                        NativeProgress.class,
                        NativeBlockStack.class,
                        NativeCustomObjective.class));
    }

    // Public native-shaped fixtures exercise reflection without packaging fake upstream classes.
    public interface NativeQuests extends Plugin {
        boolean isLoading();

        Collection<NativeQuester> getOfflineQuesters();

        NativeQuester getQuester(UUID uuid);
    }

    public interface NativeQuester {
        UUID getUUID();

        Map<NativeQuest, Integer> getCurrentQuests();

        Map<NativeQuest, NativeProgress> getQuestProgress();

        NativeStage getCurrentStage(NativeQuest quest);

        NativeProgress getQuestProgressOrDefault(NativeQuest quest);

        List<?> getCurrentObjectives(NativeQuest quest, boolean ignoreOverrides, boolean formatNames);
    }

    public interface NativeQuest {
        String getName();

        String getId();
    }

    public interface NativeBlockStack {
        Material getType();

        int getAmount();
    }

    public interface NativeCustomObjective {
        String getName();
    }

    public interface NativeStage {
        List<?> getBlocksToBreak();

        List<?> getBlocksToCut();

        List<?> getBlocksToDamage();

        List<?> getBlocksToPlace();

        List<?> getBlocksToUse();

        List<?> getCustomObjectiveCounts();

        List<?> getCustomObjectives();

        List<?> getItemsToBrew();

        List<?> getItemsToConsume();

        List<?> getItemsToCraft();

        List<?> getItemsToDeliver();

        List<?> getItemsToEnchant();

        List<?> getItemsToSmelt();

        List<?> getLocationNames();

        List<?> getMobNumToKill();

        List<?> getMobNumToTame();

        List<?> getMobsToKill();

        List<?> getMobsToTame();

        List<?> getNpcNumToKill();

        List<?> getNpcsToInteract();

        List<?> getNpcsToKill();

        List<?> getPasswordDisplays();

        List<?> getSheepNumToShear();

        List<?> getSheepToShear();

        Integer getFishToCatch();

        Integer getCowsToMilk();

        Integer getPlayersToKill();
    }

    public interface NativeProgress {
        List<?> getBlocksBroken();

        List<?> getBlocksCut();

        List<?> getBlocksDamaged();

        List<?> getBlocksPlaced();

        List<?> getBlocksUsed();

        List<?> getCustomObjectiveCounts();

        List<?> getItemsBrewed();

        List<?> getItemsConsumed();

        List<?> getItemsCrafted();

        List<?> getItemsDelivered();

        List<?> getItemsEnchanted();

        List<?> getItemsSmelted();

        List<?> getLocationsReached();

        List<?> getMobNumKilled();

        List<?> getMobsTamed();

        List<?> getNpcsInteracted();

        List<?> getNpcsNumKilled();

        List<?> getPasswordsSaid();

        List<?> getSheepSheared();

        int getFishCaught();

        int getCowsMilked();

        int getPlayersKilled();
    }
}
