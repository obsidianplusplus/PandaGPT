package cn.panda.pandagpt.ai;
import java.util.List;
public record AiResponse(String text, List<Action> actions, Integer selectedObject) {
    public record Action(String command, String reason) {}
    public AiResponse { actions = List.copyOf(actions); }
    public AiResponse(String text, List<Action> actions) { this(text, actions, null); }
}
