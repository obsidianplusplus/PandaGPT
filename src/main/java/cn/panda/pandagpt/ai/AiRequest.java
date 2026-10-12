package cn.panda.pandagpt.ai;
import java.util.List;
public record AiRequest(String systemPrompt, List<Message> messages, boolean actionsEnabled) {
    public AiRequest { messages = List.copyOf(messages); }
}
