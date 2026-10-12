package cn.panda.pandagpt.ai;
import java.util.List;
public record AiResponse(String text, List<Action> actions) {
    public record Action(String command, String reason) {}
    public AiResponse { actions = List.copyOf(actions); }
}
