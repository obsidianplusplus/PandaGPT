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
import java.util.UUID;

/** OpenAI Chat Completions 兼容格式，支持多轮工具调用与图片输入。 */
final class OpenAiFormat implements WireFormat {
    static final OpenAiFormat INSTANCE = new OpenAiFormat();

    @Override
    public URI uri(Settings settings) {
        return URI.create(settings.apiUrl());
    }

    @Override
    public void authorize(HttpRequest.Builder builder, Settings settings) {
        builder.header("Authorization", "Bearer " + settings.apiKey());
    }

    @Override
    public String body(Settings settings, AiRequest request) {
        JsonObject body = new JsonObject();
        body.addProperty("model", settings.model());
        body.addProperty("max_completion_tokens", settings.maxOutputTokens());
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", request.systemPrompt());
        messages.add(system);
        for (Message m : request.messages()) {
            messages.add(message(m));
        }
        body.add("messages", messages);
        if (!request.tools().isEmpty()) {
            JsonArray tools = new JsonArray();
            for (JsonObject function : request.tools()) {
                JsonObject tool = new JsonObject();
                tool.addProperty("type", "function");
                tool.add("function", function);
                tools.add(tool);
            }
            body.add("tools", tools);
            body.addProperty("tool_choice", request.finalAnswer() ? "none" : "auto");
        }
        return body.toString();
    }

    private static JsonObject message(Message m) {
        JsonObject message = new JsonObject();
        message.addProperty("role", m.role());
        switch (m.role()) {
            case "tool" -> {
                message.addProperty("tool_call_id", m.answers().id());
                message.addProperty("content", m.content());
            }
            case "assistant" -> {
                if (m.content().isEmpty() && !m.toolCalls().isEmpty()) {
                    message.add("content", com.google.gson.JsonNull.INSTANCE);
                } else {
                    message.addProperty("content", m.content());
                }
                if (!m.toolCalls().isEmpty()) {
                    JsonArray calls = new JsonArray();
                    for (ToolCall call : m.toolCalls()) {
                        JsonObject function = new JsonObject();
                        function.addProperty("name", call.name());
                        function.addProperty("arguments", call.arguments());
                        JsonObject item = new JsonObject();
                        item.addProperty("id", call.id());
                        item.addProperty("type", "function");
                        item.add("function", function);
                        calls.add(item);
                    }
                    message.add("tool_calls", calls);
                }
            }
            default -> {
                if (m.image() == null) {
                    message.addProperty("content", m.content());
                } else {
                    JsonArray parts = new JsonArray();
                    JsonObject text = new JsonObject();
                    text.addProperty("type", "text");
                    text.addProperty("text", m.content());
                    parts.add(text);
                    JsonObject url = new JsonObject();
                    url.addProperty(
                            "url",
                            "data:" + m.image().mediaType() + ";base64,"
                                    + m.image().base64());
                    JsonObject image = new JsonObject();
                    image.addProperty("type", "image_url");
                    image.add("image_url", url);
                    parts.add(image);
                    message.add("content", parts);
                }
            }
        }
        return message;
    }

    @Override
    public AiResponse parse(String json) throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            throw new LocalizedIOException("ai.no_candidates");
        }
        JsonObject choice = choices.get(0).getAsJsonObject();
        String finish = WireFormat.string(choice, "finish_reason");
        if (finish.equals("length") || finish.equals("content_filter")) {
            throw new LocalizedIOException("ai.truncated");
        }
        JsonObject message = choice.getAsJsonObject("message");
        List<ToolCall> calls = new ArrayList<>();
        JsonArray toolCalls = message.getAsJsonArray("tool_calls");
        if (toolCalls != null) {
            for (JsonElement element : toolCalls) {
                JsonObject call = element.getAsJsonObject();
                if (!"function".equals(WireFormat.string(call, "type"))) {
                    continue;
                }
                JsonObject function = call.getAsJsonObject("function");
                String id = WireFormat.string(call, "id");
                calls.add(new ToolCall(
                        id.isEmpty() ? ToolCall.GENERATED_PREFIX + UUID.randomUUID() : id,
                        WireFormat.string(function, "name"),
                        WireFormat.string(function, "arguments"),
                        null));
            }
        }
        String text = WireFormat.string(message, "content");
        if (text.isBlank() && calls.isEmpty()) {
            throw new LocalizedIOException("ai.empty");
        }
        return new AiResponse(text.strip(), calls, null);
    }

    private OpenAiFormat() {}
}
