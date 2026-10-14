package cn.panda.pandagpt.actions;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CommandPolicyTest {
    @Test
    void preservesSelectorsNegativeNumbersAndDecimals() {
        assertEquals("give @p stone 64", CommandPolicy.validate("/give @p stone 64", Set.of("give"), Set.of()));
        assertEquals("tp -10.5 64 20", CommandPolicy.validate("/tp -10.5 64 20", Set.of("tp"), Set.of()));
    }

    @Test
    void rejectsNamespacesControlCharactersChainingAndPrivilegeWrappers() {
        for (String command : new String[] {
            "minecraft:help",
            "help\nop x",
            "help;op x",
            "help|op x",
            "op x",
            "execute as x run help",
            "//help",
            "help\tfoo"
        })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> CommandPolicy.validate(command, Set.of("help", "op", "execute", "minecraft:help"), Set.of()));
        assertThrows(
                IllegalArgumentException.class, () -> CommandPolicy.validate("help", Set.of("help"), Set.of("help")));
    }
}
