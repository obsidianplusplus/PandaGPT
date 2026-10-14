package cn.panda.pandagpt.features;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class RemindersTest {
    @Test
    void timeAndGameEventRemindersFireOnceAndRespectTheLimit() {
        UUID id = UUID.randomUUID();
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(world.getTime()).thenReturn(12100L);
        var reminders = new Reminders();
        assertEquals(1, reminders.add(id, "收小麦", Reminders.Trigger.TIME, 60_000, 0, 2));
        assertEquals(2, reminders.add(id, "回家", Reminders.Trigger.SUNSET, 0, 0, 2));
        assertEquals(-1, reminders.add(id, "太多了", Reminders.Trigger.TIME, 0, 0, 2));

        // 设定后不足 5 秒的日落提醒不立即触发；时间提醒未到期。
        reminders.tick(u -> player, 1000);
        verify(player, never()).sendMessage(any(Component.class));
        reminders.tick(u -> player, 10_000);
        verify(player, times(1)).sendMessage(any(Component.class));
        reminders.tick(u -> player, 60_000);
        verify(player, times(2)).sendMessage(any(Component.class));
        assertTrue(reminders.list(id).isEmpty());
        assertEquals(0, reminders.active());
        assertFalse(reminders.cancel(id, 1));
    }
}
