package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.awareness.RecipeKnowledge;
import org.bukkit.Material;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class GoalProgressTest {
    private ItemStack item(Material type, int amount) { var item = mock(ItemStack.class); when(item.getType()).thenReturn(type); when(item.getAmount()).thenReturn(amount); return item; }
    @Test void possessionIncludesEquippedAndOffhandItemsButCraftIngredientsUseStorageOnly() {
        var goalItem = item(Material.IRON_PICKAXE, 1);
        assertTrue(GoalProgress.evaluate("IRON_PICKAXE", 1, new ItemStack[0], new ItemStack[]{goalItem}, List.of()).complete());
        var stick = item(Material.STICK, 2); RecipeChoice choice = mock(RecipeChoice.class); when(choice.test(stick)).thenReturn(true);
        var entry = new RecipeKnowledge.Entry(goalItem, List.of(choice, choice), "test:pick", true);
        assertFalse(GoalProgress.evaluate("IRON_PICKAXE", 1, new ItemStack[0], new ItemStack[]{stick}, List.of(entry)).ingredientsReady());
        assertEquals("DIAMOND", GoalProgress.normalize("minecraft:diamond"));
    }
    @Test void readyRequiresRealDistinctIngredientsAndCompletionUsesInventoryCount() {
        var stick = item(Material.STICK, 1); var result = item(Material.IRON_PICKAXE, 1);
        RecipeChoice choice = mock(RecipeChoice.class); when(choice.test(stick)).thenReturn(true);
        var entry = new RecipeKnowledge.Entry(result, List.of(choice, choice), "test:pickaxe", true);
        assertFalse(GoalProgress.evaluate("IRON_PICKAXE", 1, new ItemStack[]{stick}, List.of(entry)).ingredientsReady());
        when(stick.getAmount()).thenReturn(2);
        assertTrue(GoalProgress.evaluate("IRON_PICKAXE", 1, new ItemStack[]{stick}, List.of(entry)).ingredientsReady());
        assertFalse(GoalProgress.evaluate("IRON_PICKAXE", 2, new ItemStack[]{stick}, List.of(entry)).ingredientsReady());
        var complete = GoalProgress.evaluate("IRON_PICKAXE", 1, new ItemStack[]{result}, List.of(entry));
        assertTrue(complete.complete()); assertTrue(complete.detail().contains("不推断获得方式"));
    }
    @Test void unsupportedRecipeDoesNotInventARecipeOrCompletion() {
        var p = GoalProgress.evaluate("DIAMOND", 2, new ItemStack[]{item(Material.DIAMOND, 1)}, List.of());
        assertFalse(p.complete()); assertFalse(p.ingredientsReady()); assertEquals(.5, p.fraction());
        assertEquals("mine", GoalProgress.normalize("挖矿")); assertEquals("IRON_PICKAXE", GoalProgress.normalize("铁镐"));
        assertEquals("mine", GoalProgress.normalize("mine"));
    }
}
