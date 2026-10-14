package cn.panda.pandagpt.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationTest {
    @Test void keepsCompleteRoundsWithNativeRoles() {
        Conversation history = new Conversation(2);
        history.add("q1", "a1"); history.add("q2", "a2"); history.add("q3", "a3");
        var messages = history.snapshot("q4", 100);
        assertEquals(5, messages.size()); assertEquals("q2", messages.getFirst().content());
        assertEquals("user", messages.getFirst().role()); assertEquals("assistant", messages.get(1).role());
        assertEquals("q4", messages.getLast().content());
        assertThrows(UnsupportedOperationException.class, () -> messages.clear());
    }
    @Test void budgetDropsWholePairsAndZeroRoundsMeansNoMemory() {
        Conversation history = new Conversation(5);
        history.add("older", "older"); history.add("a", "b");
        assertEquals(3, history.snapshot("q", 3).size());
        assertEquals(1, history.snapshot("q", 2).size());
        Conversation empty = new Conversation(0); empty.add("a", "b");
        assertEquals(1, empty.snapshot("q", 10).size());
    }
}
