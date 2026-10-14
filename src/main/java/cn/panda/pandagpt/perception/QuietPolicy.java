package cn.panda.pandagpt.perception;

import cn.panda.pandagpt.i18n.Msg;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * 安静的主动建议调度：候选只来自实际证据，绝不按定时器生成填充内容；每位玩家最多保留一条待发送建议。
 *
 * <p>普通建议受最小间隔与每半小时条数限制；紧急的局部风险不受条数限制，但同样去重。线程：仅限服务器主线程。
 */
public final class QuietPolicy {
    /** 候选建议的来源类型，用于发送前的有效性复核。 */
    public enum Kind {
        /** 目标材料已齐。 */
        READY,
        /** 材料已齐且附近观察到工作台。 */
        TABLE,
        /** 离开基地时的采矿准备提醒。 */
        DEPARTURE,
        /** 前方局部碰撞风险。 */
        LOCAL_RISK,
        /** 生命或饥饿值偏低。 */
        VITALS,
        /** 入夜时远离家。 */
        NIGHTFALL
    }

    /**
     * 一条候选建议。
     *
     * @param key 去重键，相同事实只提醒一次
     * @param anchor 与建议相关的方块坐标，可为 null
     */
    public record Candidate(
            Kind kind,
            String key,
            Msg text,
            String goal,
            UUID world,
            SceneObjects.Pos anchor,
            long expiresAt,
            int importance,
            boolean urgent) {}

    private static final long HALF_HOUR = 1_800_000;
    private static final int MAX_REMEMBERED = 64;

    private final int maxRoutine;
    private final long spacing;
    private final Map<String, Long> delivered = new LinkedHashMap<>();
    private final ArrayDeque<Long> routine = new ArrayDeque<>();
    private Candidate pending;
    private long lastRoutine = Long.MIN_VALUE;

    public QuietPolicy(int maxRoutine, long spacing) {
        this.maxRoutine = maxRoutine;
        this.spacing = spacing;
    }

    /** 提交候选；已发送过的事实被忽略，重要性不低于当前待发送建议时替换之。 */
    public void offer(Candidate candidate, long now) {
        delivered.entrySet().removeIf(e -> now - e.getValue() > HALF_HOUR);
        if (candidate.expiresAt <= now || delivered.containsKey(candidate.key)) {
            return;
        }
        if (pending == null || pending.expiresAt <= now || candidate.importance >= pending.importance) {
            pending = candidate;
        }
    }

    /**
     * 取出当前可以发送的建议。
     *
     * @param quiet 安静模式会直接丢弃待发送建议
     * @param occupied 玩家正忙（战斗、建造、等待回答）时推迟普通建议
     * @param valid 发送前对当前游戏状态的复核
     */
    public Candidate poll(long now, boolean quiet, boolean occupied, Predicate<Candidate> valid) {
        while (!routine.isEmpty() && now - routine.getFirst() >= HALF_HOUR) {
            routine.removeFirst();
        }
        if (pending == null) {
            return null;
        }
        if (quiet || pending.expiresAt <= now || !valid.test(pending)) {
            pending = null;
            return null;
        }
        boolean throttled = occupied
                || routine.size() >= maxRoutine
                || (lastRoutine != Long.MIN_VALUE && now - lastRoutine < spacing);
        if (!pending.urgent && throttled) {
            return null;
        }
        Candidate result = pending;
        pending = null;
        delivered.put(result.key, now);
        while (delivered.size() > MAX_REMEMBERED) {
            delivered.remove(delivered.keySet().iterator().next());
        }
        if (!result.urgent) {
            routine.add(now);
            lastRoutine = now;
        }
        return result;
    }

    public void clear() {
        pending = null;
    }

    public Candidate pending() {
        return pending;
    }
}
