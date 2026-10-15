package cn.panda.pandagpt.agent;

import cn.panda.pandagpt.config.RuntimeSettings;
import com.google.gson.Gson;
import java.util.List;
import java.util.Map;

/** Model-directed working state, confined to the server thread and the player's bounded session. */
public final class AgentRuntime {
    public enum Status {
        pending,
        in_progress,
        completed,
        blocked
    }

    public enum Trigger {
        timer,
        state_change
    }

    public record Step(String description, Status status) {}

    public record Wake(String reason, Trigger trigger, long dueAt, long expiresAt, String baseline) {}

    private static final Gson GSON = new Gson();
    private final RuntimeSettings settings;
    private String objective = "";
    private String authorization = "";
    private String activity = "unknown";
    private List<Step> steps = List.of();
    private int remaining;
    private long revision;
    private Wake wake;
    private String lastDecision = "";

    public AgentRuntime(RuntimeSettings settings) {
        this.settings = settings;
    }

    /** A proactive turn may revise an existing plan, but cannot create a new objective or replenish its budget. */
    public void update(String objective, String activity, List<Step> steps, String evidence, boolean conversation) {
        if (objective == null
                || objective.isBlank()
                || objective.length() > 160
                || activity == null
                || activity.isBlank()
                || activity.length() > 80
                || steps == null
                || steps.isEmpty()
                || steps.size() > 8
                || steps.stream()
                        .anyMatch(s -> s == null
                                || s.status() == null
                                || s.description() == null
                                || s.description().isBlank()
                                || s.description().length() > 160)
                || steps.stream().filter(s -> s.status() == Status.in_progress).count() > 1) {
            throw new IllegalArgumentException(
                    "Use a short objective/activity and 1–8 steps, with at most one in_progress step.");
        }
        if (conversation) {
            if (evidence == null || evidence.isBlank() || evidence.length() > 4000) {
                throw new IllegalArgumentException("A complete current player request is required.");
            }
            if (!evidence.equals(authorization)) remaining = settings.maxFollowUps();
            authorization = evidence;
        } else if (this.objective.isEmpty() || !this.objective.equals(objective)) {
            throw new IllegalArgumentException("Proactive turns may only update the existing player's objective.");
        }
        this.objective = objective;
        this.activity = activity;
        this.steps = List.copyOf(steps);
        revision++;
        wake = null;
    }

    public void schedule(int seconds, Trigger trigger, String reason, String baseline, long now) {
        if (!settings.enabled() || objective.isEmpty() || completed() || remaining == 0) {
            throw new IllegalArgumentException("No unfinished player plan or follow-up budget remains.");
        }
        if (seconds < settings.followUpMinSeconds()
                || seconds > 1800
                || trigger == null
                || reason == null
                || reason.isBlank()
                || reason.length() > 240) {
            throw new IllegalArgumentException("Follow-up delay must be " + settings.followUpMinSeconds()
                    + "–1800 seconds; provide a concrete reason (up to 240 characters).");
        }
        long due = now + seconds * 1000L;
        wake = new Wake(reason, trigger, due, due + 1_800_000L, baseline);
    }

    /** A cheap local check never calls a model. Each accepted wake consumes one non-renewable plan allowance. */
    public Wake take(long now, String state, boolean quiet, boolean occupied) {
        if (quiet || (wake != null && now >= wake.expiresAt())) wake = null;
        if (wake == null
                || occupied
                || now < wake.dueAt()
                || (wake.trigger() == Trigger.state_change && wake.baseline().equals(state))) return null;
        Wake result = wake;
        wake = null;
        remaining--;
        return result;
    }

    public void cancelFollowUp() {
        wake = null;
    }

    public void clear() {
        objective = "";
        authorization = "";
        activity = "unknown";
        steps = List.of();
        remaining = 0;
        wake = null;
        lastDecision = "";
        revision++;
    }

    public boolean waiting() {
        return wake != null;
    }

    public boolean due(long now) {
        return wake != null && now >= wake.dueAt();
    }

    public boolean expired(long now) {
        return wake != null && now >= wake.expiresAt();
    }

    public void recordDecision(String decision) {
        lastDecision = decision.length() <= 240 ? decision : "A suggestion was delivered; its full text is omitted.";
    }

    public boolean completed() {
        return !steps.isEmpty() && steps.stream().allMatch(s -> s.status() == Status.completed);
    }

    public long revision() {
        return revision;
    }

    public String objective() {
        return objective;
    }

    public String phase() {
        if (objective.isEmpty()) return "idle";
        if (completed()) return "completed";
        if (wake != null) return "waiting";
        if (steps.stream().anyMatch(s -> s.status() == Status.blocked)
                && steps.stream().noneMatch(s -> s.status() == Status.in_progress)) return "blocked";
        return "active";
    }

    public String context() {
        return GSON.toJson(Map.of(
                "objective", objective,
                "player_request", authorization,
                "activity_interpretation", activity,
                "steps", steps,
                "phase", phase(),
                "revision", revision,
                "follow_ups_remaining", remaining,
                "next_check", wake == null ? Map.of() : wake,
                "last_proactive_decision", lastDecision));
    }
}
