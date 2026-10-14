package cn.panda.pandagpt.features;

import cn.panda.pandagpt.i18n.Lang;
import cn.panda.pandagpt.i18n.Lang.Prefix;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * 提醒：按现实时间（几分钟后）或游戏事件（日落、入夜、日出）触发，到时在聊天中提示并播放提示音。
 *
 * <p>提醒保存在内存中，服务器重启后清空；玩家离线期间到期的时间提醒会在其上线后送达。线程：仅限服务器主线程。
 */
public final class Reminders {
    /** 触发方式。 */
    public enum Trigger {
        TIME,
        SUNSET,
        NIGHT,
        SUNRISE;

        public static Trigger parse(String value) {
            return switch (value == null ? "" : value.strip().toLowerCase(Locale.ROOT)) {
                case "sunset" -> SUNSET;
                case "night" -> NIGHT;
                case "sunrise" -> SUNRISE;
                default -> TIME;
            };
        }
    }

    /** 一条提醒。 */
    public record Reminder(int id, String text, Trigger trigger, long due, long created) {}

    private final Map<UUID, List<Reminder>> reminders = new HashMap<>();
    private int nextId = 1;

    /** @return 新提醒的编号；超出上限时返回 -1 */
    public int add(UUID player, String text, Trigger trigger, long due, long now, int limit) {
        List<Reminder> list = reminders.computeIfAbsent(player, k -> new ArrayList<>());
        if (list.size() >= limit) {
            return -1;
        }
        Reminder reminder = new Reminder(nextId++, text, trigger, due, now);
        list.add(reminder);
        return reminder.id();
    }

    public List<Reminder> list(UUID player) {
        return List.copyOf(reminders.getOrDefault(player, List.of()));
    }

    public boolean cancel(UUID player, int id) {
        List<Reminder> list = reminders.get(player);
        return list != null && list.removeIf(r -> r.id() == id);
    }

    /** 每秒调用一次。 */
    public void tick(Function<UUID, Player> players, long now) {
        for (var entry : List.copyOf(reminders.entrySet())) {
            Player player = players.apply(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }
            long time = player.getWorld().getTime();
            for (Reminder reminder : List.copyOf(entry.getValue())) {
                boolean due = switch (reminder.trigger()) {
                    case TIME -> now >= reminder.due();
                    case SUNSET -> time >= 12000 && time < 13000;
                    case NIGHT -> time >= 13000 && time < 23000;
                    case SUNRISE -> time >= 23000 || time < 1000;
                };
                // 游戏事件提醒至少在设定 5 秒后才触发，避免设定时正好处于该时段而立即响起。
                if (due && (reminder.trigger() == Trigger.TIME || now - reminder.created() > 5000)) {
                    entry.getValue().remove(reminder);
                    Lang.send(player, Prefix.REMINDER, NamedTextColor.GOLD, "reminder.fired", reminder.text());
                    player.playSound(player.getLocation(), "minecraft:block.note_block.bell", 1f, 1.2f);
                }
            }
        }
        reminders.values().removeIf(List::isEmpty);
    }

    public void forget(UUID player) {
        reminders.remove(player);
    }

    public int active() {
        return reminders.values().stream().mapToInt(List::size).sum();
    }
}
