package cn.panda.pandagpt.agent;

import static org.junit.jupiter.api.Assertions.*;

import cn.panda.pandagpt.config.RuntimeSettings;
import java.util.List;
import org.junit.jupiter.api.Test;

class AgentRuntimeTest {
    private static final List<AgentRuntime.Step> STEPS = List.of(
            new AgentRuntime.Step("Collect iron", AgentRuntime.Status.in_progress),
            new AgentRuntime.Step("Craft pickaxe", AgentRuntime.Status.pending));

    private AgentRuntime runtime() {
        var runtime = new AgentRuntime(new RuntimeSettings(true, 30, 2));
        runtime.update("Make a pickaxe", "preparing", STEPS, "Help me prepare a pickaxe", true);
        return runtime;
    }

    @Test
    void aChangedStateWakesOnlyAfterTheDelayAndBusyOrQuietChecksDoNotSpendTheAllowance() {
        var runtime = runtime();
        runtime.schedule(30, AgentRuntime.Trigger.state_change, "Check iron", "old", 1000);
        assertNull(runtime.take(30000, "changed", false, false));
        assertNull(runtime.take(31000, "old", false, false));
        assertNull(runtime.take(32000, "changed", false, true));
        assertNotNull(runtime.take(32000, "changed", false, false));
        assertFalse(runtime.waiting());
        assertTrue(runtime.context().contains("\"follow_ups_remaining\":1"));
        runtime.schedule(30, AgentRuntime.Trigger.timer, "Check crafting", "old", 40000);
        assertNull(runtime.take(80000, "changed", true, false));
        assertFalse(runtime.waiting());
        assertTrue(runtime.context().contains("\"follow_ups_remaining\":1"));
    }

    @Test
    void proactivePlanUpdatesCannotInventTasksOrRefillTheFiniteAllowance() {
        var runtime = runtime();
        for (int i = 0; i < 2; i++) {
            runtime.schedule(30, AgentRuntime.Trigger.timer, "Check crafting", "old", i * 40000);
            assertNotNull(runtime.take(i * 40000 + 30000, "old", false, false));
            runtime.update("Make a pickaxe", "crafting", STEPS, "", false);
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.schedule(30, AgentRuntime.Trigger.timer, "Again", "old", 90000));
        assertThrows(
                IllegalArgumentException.class, () -> runtime.update("Start raiding", "exploring", STEPS, "", false));
        var empty = new AgentRuntime(RuntimeSettings.defaults());
        assertThrows(
                IllegalArgumentException.class, () -> empty.update("Invented task", "exploring", STEPS, "", false));
    }

    @Test
    void completionExpiryAndClearPreventAnyLaterWakeup() {
        var runtime = runtime();
        runtime.schedule(30, AgentRuntime.Trigger.timer, "Check", "old", 0);
        assertNull(runtime.take(1830000, "old", false, false));
        runtime.schedule(30, AgentRuntime.Trigger.timer, "Check", "old", 2000000);
        runtime.update(
                "Make a pickaxe",
                "finished",
                List.of(new AgentRuntime.Step("Pickaxe in inventory", AgentRuntime.Status.completed)),
                "",
                false);
        assertTrue(runtime.completed());
        assertFalse(runtime.waiting());
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.schedule(30, AgentRuntime.Trigger.timer, "Check", "old", 2000000));
        runtime.clear();
        assertFalse(runtime.context().contains("Help me prepare"));
        assertEquals("", runtime.objective());
    }

    @Test
    void invalidPlanUpdatesAreAtomicAndASecondPlayerRequestCanReplaceTheTask() {
        var runtime = runtime();
        String before = runtime.context();
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime.update("Wrong", "unknown", List.of(), "player text", true));
        assertEquals(before, runtime.context());
        runtime.schedule(30, AgentRuntime.Trigger.timer, "Check", "old", 0);
        runtime.update("Prepare to travel", "preparing", STEPS, "Now help me travel", true);
        assertFalse(runtime.waiting());
        assertTrue(runtime.context().contains("\"follow_ups_remaining\":2"));
    }
}
