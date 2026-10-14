package cn.panda.pandagpt.perception;

import java.util.*;
import java.util.function.Predicate;

/** Evidence-driven candidates, never a timer that generates filler. One pending suggestion per player. */
public final class QuietPolicy {
    public record Candidate(String key, String text, String goal, UUID world, SceneObjects.Pos anchor,
                            long expiresAt, int importance, boolean urgent) {}
    private final int maxRoutine; private final long spacing;
    private final Map<String, Long> delivered = new LinkedHashMap<>();
    private final ArrayDeque<Long> routine = new ArrayDeque<>();
    private Candidate pending; private long lastRoutine = Long.MIN_VALUE;
    public QuietPolicy(int maxRoutine, long spacing) { this.maxRoutine = maxRoutine; this.spacing = spacing; }
    public void offer(Candidate candidate, long now) {
        delivered.entrySet().removeIf(e -> now - e.getValue() > 1_800_000);
        if (candidate.expiresAt <= now || delivered.containsKey(candidate.key)) return;
        if (pending == null || pending.expiresAt <= now || candidate.importance >= pending.importance) pending = candidate;
    }
    public Candidate poll(long now, boolean quiet, boolean occupied, Predicate<Candidate> valid) {
        while (!routine.isEmpty() && now - routine.getFirst() >= 1_800_000) routine.removeFirst();
        if (pending == null) return null;
        if (quiet || pending.expiresAt <= now || !valid.test(pending)) { pending = null; return null; }
        if (!pending.urgent && (occupied || routine.size() >= maxRoutine || lastRoutine != Long.MIN_VALUE && now - lastRoutine < spacing)) return null;
        Candidate result = pending; pending = null;
        delivered.put(result.key, now); while (delivered.size() > 64) delivered.remove(delivered.keySet().iterator().next());
        if (!result.urgent) { routine.add(now); lastRoutine = now; }
        return result;
    }
    public void clear() { pending = null; }
    public Candidate pending() { return pending; }
}
