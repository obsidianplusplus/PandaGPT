package cn.panda.pandagpt.actions;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.TestSupport;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.util.PandaLogger;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class ActionControllerTest {
    /** 记录每个按钮的点击回调：偶数位是确认，奇数位是取消。 */
    private static final class FakeButtons implements ActionController.Buttons {
        final List<Consumer<Audience>> clicks = new ArrayList<>();

        @Override
        public ClickEvent<?> create(Consumer<Audience> onClick, Duration lifetime) {
            clicks.add(onClick);
            return ClickEvent.suggestCommand("button-" + clicks.size());
        }

        void confirm(Audience who) {
            clicks.get(clicks.size() - 2).accept(who);
        }

        void cancel(Audience who) {
            clicks.getLast().accept(who);
        }
    }

    private static Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getServer()).thenReturn(mock(Server.class));
        when(p.isOnline()).thenReturn(true);
        when(p.hasPermission(anyString())).thenReturn(true);
        when(p.performCommand(anyString())).thenReturn(true);
        return p;
    }

    private static Settings enabled() {
        var config = TestSupport.config();
        config.set("panda.command_awareness.allow_execution", true);
        return Settings.load(config);
    }

    @Test
    void proposalRunsOnlyWhenItsOwnerClicksConfirmAndCannotBeReplayed() {
        Player player = player();
        var buttons = new FakeButtons();
        var controller = new ActionController(enabled(), mock(PandaLogger.class), Clock.systemUTC(), buttons);
        controller.propose(player, List.of(new ProposedCommand("/help", "show help")));
        verify(player, never()).performCommand(anyString());
        buttons.confirm(player());
        verify(player, never()).performCommand(anyString());
        buttons.confirm(player);
        buttons.confirm(player);
        verify(player, times(1)).performCommand("help");
    }

    @Test
    void cancelButtonAndNaturalLanguageConfirmationShareTheSamePendingBatch() {
        Player player = player();
        var buttons = new FakeButtons();
        var controller = new ActionController(enabled(), mock(PandaLogger.class), Clock.systemUTC(), buttons);
        controller.propose(player, List.of(new ProposedCommand("help", "")));
        assertEquals(List.of("help"), controller.pendingCommands(player.getUniqueId()));
        buttons.cancel(player);
        assertFalse(controller.hasPending(player.getUniqueId()));
        controller.confirmPending(player);
        verify(player, never()).performCommand(anyString());

        controller.propose(player, List.of(new ProposedCommand("help", "")));
        controller.confirmPending(player);
        verify(player, times(1)).performCommand("help");
        assertFalse(controller.hasPending(player.getUniqueId()));
    }

    @Test
    void bothDisableSwitchesPreventDispatch() {
        for (String switchName :
                List.of("panda.command_awareness.enabled", "panda.command_awareness.allow_execution")) {
            var config = TestSupport.config();
            config.set("panda.command_awareness.allow_execution", true);
            config.set(switchName, false);
            Player p = player();
            var controller = new ActionController(
                    Settings.load(config), mock(PandaLogger.class), Clock.systemUTC(), new FakeButtons());
            controller.propose(p, List.of(new ProposedCommand("help", "")));
            controller.confirmPending(p);
            verify(p, never()).performCommand(anyString());
            assertFalse(controller.enabled(p));
        }
    }

    @Test
    void expiryAndPermissionRevocationRejectConfirmedActions() {
        class MutableClock extends Clock {
            Instant now = Instant.EPOCH;

            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            public Clock withZone(ZoneId zone) {
                return this;
            }

            public Instant instant() {
                return now;
            }
        }
        MutableClock clock = new MutableClock();
        Player p = player();
        var buttons = new FakeButtons();
        var controller = new ActionController(enabled(), mock(PandaLogger.class), clock, buttons);
        controller.propose(p, List.of(new ProposedCommand("help", "")));
        clock.now = Instant.EPOCH.plusSeconds(61);
        buttons.confirm(p);
        verify(p, never()).performCommand(anyString());
        controller.propose(p, List.of(new ProposedCommand("help", "")));
        when(p.hasPermission("pandagpt.actions")).thenReturn(false);
        buttons.confirm(p);
        verify(p, never()).performCommand(anyString());
    }

    @Test
    void aBlockedActionRejectsTheWholeBatch() {
        Player p = player();
        var buttons = new FakeButtons();
        var controller = new ActionController(enabled(), mock(PandaLogger.class), Clock.systemUTC(), buttons);
        controller.propose(p, List.of(new ProposedCommand("help", ""), new ProposedCommand("op somebody", "")));
        verify(p, never()).performCommand(anyString());
        assertTrue(buttons.clicks.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> controller.validate(p, "op somebody"));
        assertEquals("help", controller.validate(p, "/HELP"));
    }
}
