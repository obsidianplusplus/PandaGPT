package cn.panda.pandagpt.awareness;

import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.RecipeChoice;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RecipeKnowledgeTest {
    @org.junit.jupiter.api.Test void repeatedBatchChoicesAreTestedOncePerInventorySlotAndDeadlineIsConservative() {
        var choice = mock(org.bukkit.inventory.RecipeChoice.MaterialChoice.class); var item = mock(org.bukkit.inventory.ItemStack.class);
        when(item.getAmount()).thenReturn(9); when(choice.test(item)).thenReturn(true);
        assertTrue(RecipeKnowledge.matches(java.util.Collections.nCopies(9, choice), new org.bukkit.inventory.ItemStack[]{item}));
        verify(choice, times(1)).test(item);
        assertFalse(RecipeKnowledge.matches(java.util.List.of(choice), new org.bukkit.inventory.ItemStack[]{item}, () -> false));
        verify(choice, times(1)).test(item);
    }
    @Test void ingredientsAreNotDoubleCountedAndAllocationCanBacktrack() {
        ItemStack oak = mock(ItemStack.class), birch = mock(ItemStack.class);
        when(oak.getAmount()).thenReturn(1); when(birch.getAmount()).thenReturn(1);
        RecipeChoice any = mock(RecipeChoice.class), onlyOak = mock(RecipeChoice.class);
        when(any.test(oak)).thenReturn(true); when(any.test(birch)).thenReturn(true); when(onlyOak.test(oak)).thenReturn(true);
        assertTrue(RecipeKnowledge.matches(List.of(any, onlyOak), new ItemStack[]{oak, birch}));
        assertFalse(RecipeKnowledge.matches(List.of(onlyOak, onlyOak), new ItemStack[]{oak, birch}));
    }
}
