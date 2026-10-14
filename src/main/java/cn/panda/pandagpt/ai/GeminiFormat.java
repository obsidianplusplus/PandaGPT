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

/**
 * Google Gemini generateContent 格式，支持多轮函数调用与图片输入。
 *
 * <p>Gemini 3 要求在后续请求中原样带回函数调用的思考签名（thoughtSignature），否则会拒绝请求；相邻的同角色内容会合并。
 */
final class GeminiFormat implements WireFormat {
    static final GeminiFormat INSTANCE = new GeminiFormat();

    @Override
    public URI uri(Settings settings) {
        return URI.create(settings.apiUrl().replace("%s", settings.model()));
    }

    @Override
    public void authorize(HttpRequest.Builder builder, Settings settings) {
        builder.header("x-goog-api-key", settings.apiKey());
    }

    @Override
    public String body(Settings settings, AiRequest request) {
        JsonObject body = new JsonObject();
        JsonObject system = new JsonObject();
        JsonArray systemParts = new JsonArray();
        systemParts.add(text(request.systemPrompt(), null));
        system.add("parts", systemParts);
        body.add("systemInstruction", system);
        JsonArray contents = new JsonArray();
        JsonObject current = null;
        String currentRole = null;
        for (Message m : request.messages()) {
            // 工具结果在 Gemini 中以 user 角色的 functionResponse 发送。
            String role = m.role().equals("assistant") ? "model" : "user";
            if (current == null || !role.equals(currentRole)) {
                current = new JsonObject();
                current.addProperty("role", role);
                current.add("parts", new JsonArray());
                contents.add(current);
                currentRole = role;
            }
            JsonArray parts = current.getAsJsonArray("parts");
            switch (m.role()) {
                case "tool" -> {
                    JsonObject response = new JsonObject();
                    response.addProperty("name", m.answers().name());
                    if (!m.answers().id().startsWith(ToolCall.GENERATED_PREFIX)) {
                        response.addProperty("id", m.answers().id());
                    }
                    JsonObject result = new JsonObject();
                    result.addProperty("content", m.content());
                    response.add("response", result);
                    JsonObject part = new JsonObject();
                    part.add("functionResponse", response);
                    parts.add(part);
                }
                case "assistant" -> {
                    if (!m.content().isEmpty()) {
                        parts.add(text(m.content(), m.signature()));
                    }
                    for (ToolCall call : m.toolCalls()) {
                        JsonObject function = new JsonObject();
                        function.addProperty("name", call.name());
                        if (!call.id().startsWith(ToolCall.GENERATED_PREFIX)) {
                            function.addProperty("id", call.id());
                        }
                        String arguments = call.arguments().isBlank() ? "{}" : call.arguments();
                        function.add("args", JsonParser.parseString(arguments));
                        JsonObject part = new JsonObject();
                        part.add("functionCall", function);
                        if (call.signature() != null) {
                            part.addProperty("thoughtSignature", call.signature());
                        }
                        parts.add(part);
                    }
                }
                default -> {
                    boolean sendImage = m.image() != null && settings.visionInput();
                    String content = m.image() != null && !sendImage ? m.content() + "\n" + IMAGE_OMITTED : m.content();
                    parts.add(text(content, null));
                    if (sendImage) {
                        JsonObject data = new JsonObject();
                        data.addProperty("mimeType", m.image().mediaType());
                        data.addProperty("data", m.image().base64());
                        JsonObject image = new JsonObject();
                        image.add("inlineData", data);
                        parts.add(image);
                    }
                }
            }
        }
        body.add("contents", contents);
        JsonObject generation = new JsonObject();
        generation.addProperty("maxOutputTokens", request.outputTokens(settings.maxOutputTokens()));
        body.add("generationConfig", generation);
        if (!request.tools().isEmpty()) {
            JsonArray declarations = new JsonArray();
            request.tools().forEach(declarations::add);
            JsonObject tool = new JsonObject();
            tool.add("functionDeclarations", declarations);
            JsonArray tools = new JsonArray();
            tools.add(tool);
            body.add("tools", tools);
            JsonObject calling = new JsonObject();
            calling.addProperty("mode", request.finalAnswer() ? "NONE" : "AUTO");
            JsonObject config = new JsonObject();
            config.add("functionCallingConfig", calling);
            body.add("toolConfig", config);
        }
        return body.toString();
    }

    private static JsonObject text(String text, String signature) {
        JsonObject part = new JsonObject();
        part.addProperty("text", text);
        if (signature != null) {
            part.addProperty("thoughtSignature", signature);
        }
        return part;
    }

    @Override
    public AiResponse parse(String json) throws IOException {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonArray candidates = root.getAsJsonArray("candidates");
        if (candidates == null || candidates.isEmpty()) {
            throw new LocalizedIOException("ai.blocked");
        }
        JsonObject candidate = candidates.get(0).getAsJsonObject();
        String reason = WireFormat.string(candidate, "finishReason");
        if (!reason.isEmpty() && !reason.equals("STOP")) {
            throw new LocalizedIOException("ai.incomplete");
        }
        StringBuilder text = new StringBuilder();
        String textSignature = null;
        List<ToolCall> calls = new ArrayList<>();
        for (JsonElement element : candidate.getAsJsonObject("content").getAsJsonArray("parts")) {
            JsonObject part = element.getAsJsonObject();
            String signature =
                    part.has("thoughtSignature") ? part.get("thoughtSignature").getAsString() : null;
            JsonObject call = part.getAsJsonObject("functionCall");
            if (call != null) {
                String id = WireFormat.string(call, "id");
                JsonElement args = call.get("args");
                calls.add(new ToolCall(
                        id.isEmpty() ? ToolCall.GENERATED_PREFIX + UUID.randomUUID() : id,
                        WireFormat.string(call, "name"),
                        args == null || args.isJsonNull() ? "{}" : args.toString(),
                        signature));
                continue;
            }
            // 跳过思考过程片段，只保留最终回答。
            if (!part.has("thought") || !part.get("thought").getAsBoolean()) {
                text.append(WireFormat.string(part, "text"));
                if (signature != null && textSignature == null) {
                    textSignature = signature;
                }
            }
        }
        if (text.toString().isBlank() && calls.isEmpty()) {
            throw new LocalizedIOException("ai.empty");
        }
        return new AiResponse(text.toString().strip(), calls, textSignature);
    }

    private GeminiFormat() {}
}
