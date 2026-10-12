package cn.panda.pandagpt.actions;

import cn.panda.pandagpt.TestSupport;
import cn.panda.pandagpt.ai.AiResponse;
import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.util.PandaLogger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ActionControllerTest {
    private static Player player() {
        Player p = mock(Player.class); when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.getServer()).thenReturn(mock(Server.class)); when(p.isOnline()).thenReturn(true);
        when(p.hasPermission(anyString())).thenReturn(true); when(p.performCommand(anyString())).thenReturn(true);
        return p;
    }
    private static Settings enabled() {
        var config = TestSupport.config(); config.set("panda.command_awareness.allow_execution", true);
        return Settings.load(config);
    }
    private static String confirmation(Player p) {
        var captor = ArgumentCaptor.forClass(Component.class);
        verify(p, atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().stream().map(Component::clickEvent).filter(Objects::nonNull)
                .map(event -> ((ClickEvent.Payload.Text) event.payload()).value())
                .filter(value -> value.startsWith("/panda confirm ")).findFirst().orElseThrow().substring(15);
    }
    @Test void proposalRequiresExplicitConfirmationAndCannotBeReplayedOrUsedByAnotherPlayer() {
        Player player = player(); ActionController controller = new ActionController(enabled(), mock(PandaLogger.class));
        controller.propose(player, List.of(new AiResponse.Action("/help", "show help")));
        verify(player, never()).performCommand(anyString());
        String code = confirmation(player);
        controller.confirm(player(), code); verify(player, never()).performCommand(anyString());
        controller.confirm(player, code); controller.confirm(player, code);
        verify(player, times(1)).performCommand("help");
    }
    @Test void bothDisableSwitchesPreventDispatch() {
        for (String switchName : List.of("panda.command_awareness.enabled", "panda.command_awareness.allow_execution")) {
            var config = TestSupport.config(); config.set("panda.command_awareness.allow_execution", true); config.set(switchName, false);
            Player p = player(); var controller = new ActionController(Settings.load(config), mock(PandaLogger.class));
            controller.propose(p, List.of(new AiResponse.Action("help", ""))); controller.confirm(p, "anything");
            verify(p, never()).performCommand(anyString());
        }
    }
    @Test void expiryAndPermissionRevocationRejectConfirmedActions() {
        class MutableClock extends Clock {
            Instant now = Instant.EPOCH;
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        MutableClock clock = new MutableClock();
        Player p = player(); var controller = new ActionController(enabled(), mock(PandaLogger.class), clock);
        controller.propose(p, List.of(new AiResponse.Action("help", ""))); String expired = confirmation(p);
        clock.now = Instant.EPOCH.plusSeconds(61); controller.confirm(p, expired);
        verify(p, never()).performCommand(anyString());
        controller.propose(p, List.of(new AiResponse.Action("help", ""))); String revoked = confirmation(p);
        when(p.hasPermission("pandagpt.actions")).thenReturn(false); controller.confirm(p, revoked);
        verify(p, never()).performCommand(anyString());
    }
    @Test void aBlockedActionRejectsTheWholeBatch() {
        Player p = player(); var controller = new ActionController(enabled(), mock(PandaLogger.class));
        controller.propose(p, List.of(new AiResponse.Action("help", ""), new AiResponse.Action("op somebody", "")));
        verify(p, never()).performCommand(anyString());
        assertThrows(NoSuchElementException.class, () -> confirmation(p));
    }
}
