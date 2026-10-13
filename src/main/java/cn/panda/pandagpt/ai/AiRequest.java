package cn.panda.pandagpt.ai;
import java.util.List;
public record AiRequest(String systemPrompt, List<Message> messages, boolean actionsEnabled, Image image) {
    public record Image(String mediaType, String base64) {
        public Image {
            if (!"image/png".equals(mediaType) || base64 == null || base64.length() > 4_194_304)
                throw new IllegalArgumentException("Invalid vision image");
        }
    }
    public AiRequest(String systemPrompt, List<Message> messages, boolean actionsEnabled) {
        this(systemPrompt, messages, actionsEnabled, null);
    }
    public AiRequest { messages = List.copyOf(messages); }
}
