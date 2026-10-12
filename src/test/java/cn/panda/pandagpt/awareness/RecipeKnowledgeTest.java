package cn.panda.pandagpt.awareness;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecipeKnowledgeTest {
    @Test void ingredientsAreNotDoubleCountedAndAllocationCanBacktrack() {
        ItemStack oak = mock(ItemStack.class), birch = mock(ItemStack.class);
        when(oak.getAmount()).thenReturn(1); when(birch.getAmount()).thenReturn(1);
        RecipeChoice any = mock(RecipeChoice.class), onlyOak = mock(RecipeChoice.class);
        when(any.test(oak)).thenReturn(true); when(any.test(birch)).thenReturn(true); when(onlyOak.test(oak)).thenReturn(true);
        assertTrue(RecipeKnowledge.matches(List.of(any, onlyOak), new ItemStack[]{oak, birch}));
        assertFalse(RecipeKnowledge.matches(List.of(onlyOak, onlyOak), new ItemStack[]{oak, birch}));
    }
}
