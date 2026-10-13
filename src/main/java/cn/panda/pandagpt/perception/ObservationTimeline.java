package cn.panda.pandagpt.perception;

import java.util.*;

/** Last two compatible observations; no inferred disappearance from unobserved/unknown pixels. Main-thread only. */
public final class ObservationTimeline {
    public record Changes(int changedBlocks, List<String> events) { public Changes { events = List.copyOf(events); } }
    private final Map<UUID, Observation> latest = new LinkedHashMap<>();
    private final Map<UUID, Changes> changes = new HashMap<>();
    private final Set<UUID> invalidated = new HashSet<>();
    private final int maxPlayers;
    public ObservationTimeline(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public Changes accept(UUID player, Observation now) {
        Observation before = latest.get(player); List<String> events = new ArrayList<>(); int blocks = 0;
        if (before != null && before.world().equals(now.world()) && now.time() - before.time() > 0 && now.time() - before.time() <= 60000) {
            boolean sameView = before.camera().width() == now.camera().width() && before.camera().height() == now.camera().height()
                    && before.range() == now.range() && before.camera().verticalFov() == now.camera().verticalFov()
                    && before.camera().origin().distance(now.camera().origin()) < .01 && before.camera().forward().dot(now.camera().forward()) > .999999;
            if (sameView && before.surfaces().unknownPixels() == 0 && now.surfaces().unknownPixels() == 0
                    && before.surfaces().openingPixels() > now.surfaces().openingPixels())
                events.add("Same-view observed opening candidate pixels decreased " + before.surfaces().openingPixels() + " -> "
                        + now.surfaces().openingPixels() + "; not proof of a complete building seal.");
            if (sameView && !before.spatial().enclosure().status().equals(now.spatial().enclosure().status()))
                events.add("Same-position local empty-cell connectivity changed " + before.spatial().enclosure().status() + " -> " + now.spatial().enclosure().status()
                        + "; local collision scope only.");
            for (var entry : now.observedBlocks().entrySet()) {
                String previous = before.observedBlocks().get(entry.getKey());
                if (previous != null && !previous.equals(entry.getValue())) { blocks++; if (events.size() < 6) events.add(entry.getKey() + ": " + previous + " -> " + entry.getValue()); }
            }
            for (EntityObservation e : now.entities()) {
                if (!e.visibility().equals("VISIBLE_SAMPLES")) continue;
                EntityObservation old = before.entities().stream().filter(p -> p.uuid().equals(e.uuid()) && p.visibility().equals("VISIBLE_SAMPLES")).findFirst().orElse(null);
                if (old == null) continue;
                double seconds = (e.observedAt() - old.observedAt()) / 1000.0;
                if (seconds < .5 || seconds > 60) continue;
                // Compare both entity positions against the same current eye, avoiding a false approach from player movement.
                double approach = (old.center().distance(now.camera().origin()) - e.center().distance(now.camera().origin())) / seconds;
                if (approach > .3 && events.size() < 12) events.add(e.type() + " uuid=" + e.uuid() + " moved toward current eye at approx " + Math.round(approach * 10) / 10.0 + " blocks/s; targeting=" + e.targetingPlayer());
            }
        }
        if (!latest.containsKey(player) && latest.size() >= maxPlayers) remove(latest.keySet().iterator().next());
        latest.put(player, now); invalidated.remove(player);
        Changes result = new Changes(blocks, events); changes.put(player, result); return result;
    }
    public Observation get(UUID player, UUID world, long now, long ttl) {
        Observation o = latest.get(player);
        return o != null && o.world().equals(world) && now - o.time() <= ttl && !invalidated.contains(player) ? o : null;
    }
    public String context(UUID player) { Changes c = changes.get(player); return c == null ? "" : "Recent observation differences: " + c; }
    public void invalidate(UUID world, SceneObjects.Pos pos) {
        latest.forEach((id, o) -> { if (o.world().equals(world) && o.observedBlocks().containsKey(pos)) invalidated.add(id); });
    }
    public void remove(UUID player) { latest.remove(player); changes.remove(player); invalidated.remove(player); }
    public void clear() { latest.clear(); changes.clear(); invalidated.clear(); }
}
