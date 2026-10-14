package cn.panda.pandagpt.ai;

import cn.panda.pandagpt.config.Settings;
import cn.panda.pandagpt.i18n.LocalizedIOException;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Anthropic Claude Messages 格式，支持多轮工具调用与图片输入。
 *
 * <p>Claude 要求 user 与 assistant 严格交替，工具结果以 user 角色的 tool_result 内容块发送，因此相邻的同角色消息会合并；
 * 同一条 user 消息中工具结果排在文字与图片之前。
 */
final class AnthropicFormat implements WireFormat {
    static final AnthropicFormat INSTANCE = new AnthropicFormat();
    private static final String API_VERSION = "2023-06-01";

    @Override
    public URI uri(Settings settings) {
        return URI.create(settings.apiUrl());
    }

    @Override
    public void authorize(HttpRequest.Builder builder, Settings settings) {
        builder.header("x-api-key", settings.apiKey());
        builder.header("anthropic-version", API_VERSION);
    }

    @Override
    public String body(Settings settings, AiRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("max_tokens", request.outputTokens(settings.maxOutputTokens()));
        body.addProperty("system", request.systemPrompt());
        JsonArray messages = new JsonArray();
        JsonObject current = null;
        String currentRole = null;
        for (Message m : request.messages()) {
            String role = m.role().equals("assistant") ? "assistant" : "user";
            if (current == null || !role.equals(currentRole)) {
                current = new JsonObject();
                current.addProperty("role", role);
                current.add("content", new JsonArray());
                messages.add(current);
                currentRole = role;
            }
            JsonArray content = current.getAsJsonArray("content");
            switch (m.role()) {
                case "tool" -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("type", "tool_result");
                    result.addProperty("tool_use_id", m.answers().id());
                    result.addProperty("content", m.content().isEmpty() ? "{}" : m.content());
                    content.add(result);
                }
                case "assistant" -> {
                    if (!m.content().isEmpty()) {
                        content.add(text(m.content()));
                    }
                    for (ToolCall call : m.toolCalls()) {
                        JsonObject use = new JsonObject();
                        use.addProperty("type", "tool_use");
                        use.addProperty("id", call.id());
                        use.addProperty("name", call.name());
                        String arguments = call.arguments().isBlank() ? "{}" : call.arguments();
                        use.add("input", JsonParser.parseString(arguments));
                        content.add(use);
                    }
                }
                default -> {
                    boolean sendImage = m.image() != null && settings.visionInput();
                    String value = m.image() != null && !sendImage ? m.content() + "\n" + IMAGE_OMITTED : m.content();
                    // Claude 拒绝空文本块。
                    content.add(text(value.isEmpty() ? "." : value));
                    if (sendImage) {
                        JsonObject source = new JsonObject();
                        source.addProperty("type", "base64");
                        source.addProperty("media_type", m.image().mediaType());
                        source.addProperty("data", m.image().base64());
                        JsonObject image = new JsonObject();
                        image.addProperty("type", "image");
                        image.add("source", source);
                        content.add(image);
                    }
                }
            }
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (JsonObject function : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("name", WireFormat.string(function, "name"));
                tool.addProperty("description", WireFormat.string(function, "description"));
                tool.add("input_schema", function.get("parameters"));
                tools.add(tool);
            }
            body.add("tools", tools);
            JsonObject choice = new JsonObject();
            choice.addProperty("type", request.finalAnswer() ? "none" : "auto");
            body.add("tool_choice", choice);
        }
        return body.toString();
    }

    private static JsonObject text(String text) {
        JsonObject block = new JsonObject();
        block.addProperty("type", "text");
        block.addProperty("text", text);
        return block;
    }

    @Override
    public AiResponse parse(String json) throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String stop = WireFormat.string(root, "stop_reason");
        if (stop.equals("max_tokens")) {
            throw new LocalizedIOException("ai.truncated");
        }
        if (stop.equals("refusal")) {
            throw new LocalizedIOException("ai.blocked");
        }
        JsonArray content = root.getAsJsonArray("content");
        if (content == null) {
            throw new LocalizedIOException("ai.no_candidates");
        }
        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement element : content) {
            JsonObject block = element.getAsJsonObject();
            switch (WireFormat.string(block, "type")) {
                case "text" -> text.append(WireFormat.string(block, "text"));
                case "tool_use" -> {
                    JsonElement input = block.get("input");
                    calls.add(new ToolCall(
                            WireFormat.string(block, "id"),
                            WireFormat.string(block, "name"),
                            input == null || input.isJsonNull() ? "{}" : input.toString(),
                            null));
                }
                default -> {
                    // 思考过程等其他内容块不交给玩家。
                }
            }
        }
        if (text.toString().isBlank() && calls.isEmpty()) {
            throw new LocalizedIOException("ai.empty");
        }
        return new AiResponse(text.toString().strip(), calls, null);
    }

    private AnthropicFormat() {}
}
