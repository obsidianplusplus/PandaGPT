package cn.panda.pandagpt.ai;

import cn.panda.pandagpt.config.Settings;
import com.google.gson.*;
import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** One reusable HTTP client per configuration generation; all payloads have real role boundaries. */
public final class HttpAiProvider implements AiProvider {
    public static final String ACTION_TOOL = "propose_player_command";
    private final Settings settings;
    private final HttpClient client;

    public HttpAiProvider(Settings settings) {
        this.settings = settings;
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(settings.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        Settings.Proxy proxy = settings.proxy();
        if (proxy.enabled()) {
            builder.proxy(ProxySelector.of(new InetSocketAddress(proxy.host(), proxy.port())));
            if (!proxy.username().isBlank()) builder.authenticator(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    if (getRequestorType() == RequestorType.PROXY
                            && proxy.host().equalsIgnoreCase(getRequestingHost()) && proxy.port() == getRequestingPort())
                        return new PasswordAuthentication(proxy.username(), proxy.password().toCharArray());
                    return null;
                }
            });
        }
        client = builder.build();
    }

    HttpAiProvider(Settings settings, HttpClient client) { this.settings = settings; this.client = client; }

    @Override public AiResponse generate(AiRequest request) throws Exception {
        boolean openai = settings.provider().equals("openai");
        URI uri = URI.create(openai ? settings.apiUrl() : settings.apiUrl().replace("%s", settings.model()));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(settings.requestTimeout())
                .header("Content-Type", "application/json")
                .header(openai ? "Authorization" : "x-goog-api-key", openai ? "Bearer " + settings.apiKey() : settings.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(buildBody(settings, request), StandardCharsets.UTF_8));
        var future = client.sendAsync(builder.build(), info -> new LimitedBody(settings.maxResponseBytes()));
        HttpResponse<byte[]> response;
        try {
            // Covers body consumption too; socket-connect timeout alone does not bound a slow response.
            response = future.get(settings.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IOException("请求超时，请稍后再试。");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            throw new IOException("网络连接失败或响应超过大小限制。");
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String message = switch (status) {
                case 401, 403 -> "API 鉴权失败，请联系管理员检查密钥与权限。";
                case 404 -> "API 地址或模型不存在，请联系管理员。";
                case 429 -> "API 请求受限，请稍后再试。";
                default -> status >= 500 ? "模型服务暂时不可用，请稍后再试。" : "API 请求被拒绝，请联系管理员检查配置。";
            };
            throw new IOException(message + " (HTTP " + status + ")");
        }
        return parseResponse(settings.provider(), new String(response.body(), StandardCharsets.UTF_8), settings.maxActions());
    }

    private static JsonObject actionSchema() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        for (String name : List.of("command", "reason")) {
            JsonObject property = new JsonObject(); property.addProperty("type", "string"); properties.add(name, property);
        }
        schema.add("properties", properties);
        JsonArray required = new JsonArray(); required.add("command"); required.add("reason"); schema.add("required", required);
        return schema;
    }

    public static String buildBody(Settings settings, AiRequest request) {
        JsonObject body = new JsonObject();
        if (settings.provider().equals("openai")) {
            body.addProperty("model", settings.model());
            body.addProperty("max_completion_tokens", settings.maxOutputTokens());
            JsonArray messages = new JsonArray();
            JsonObject system = new JsonObject(); system.addProperty("role", "system"); system.addProperty("content", request.systemPrompt()); messages.add(system);
            for (int i = 0; i < request.messages().size(); i++) {
                Message m = request.messages().get(i);
                JsonObject message = new JsonObject(); message.addProperty("role", m.role());
                if (request.image() != null && i == request.messages().size() - 1 && m.role().equals("user")) {
                    JsonArray parts = new JsonArray();
                    JsonObject text = new JsonObject(); text.addProperty("type", "text"); text.addProperty("text", m.content()); parts.add(text);
                    JsonObject url = new JsonObject(); url.addProperty("url", "data:" + request.image().mediaType() + ";base64," + request.image().base64());
                    JsonObject image = new JsonObject(); image.addProperty("type", "image_url"); image.add("image_url", url); parts.add(image);
                    message.add("content", parts);
                } else message.addProperty("content", m.content());
                messages.add(message);
            }
            body.add("messages", messages);
            if (request.actionsEnabled()) {
                JsonObject function = new JsonObject(); function.addProperty("name", ACTION_TOOL);
                function.addProperty("description", "Propose a player command ONLY when the user explicitly requests execution. The player must confirm.");
                function.add("parameters", actionSchema());
                JsonObject tool = new JsonObject(); tool.addProperty("type", "function"); tool.add("function", function);
                JsonArray tools = new JsonArray(); tools.add(tool); body.add("tools", tools);
                body.addProperty("tool_choice", "auto");
                body.addProperty("parallel_tool_calls", false);
            }
        } else {
            JsonObject system = new JsonObject(); system.add("parts", textParts(request.systemPrompt())); body.add("systemInstruction", system);
            JsonArray contents = new JsonArray();
            for (int i = 0; i < request.messages().size(); i++) {
                Message m = request.messages().get(i);
                JsonObject content = new JsonObject(); content.addProperty("role", m.role().equals("assistant") ? "model" : "user");
                JsonArray parts = textParts(m.content());
                if (request.image() != null && i == request.messages().size() - 1 && m.role().equals("user")) {
                    JsonObject data = new JsonObject(); data.addProperty("mimeType", request.image().mediaType()); data.addProperty("data", request.image().base64());
                    JsonObject image = new JsonObject(); image.add("inlineData", data); parts.add(image);
                }
                content.add("parts", parts); contents.add(content);
            }
            body.add("contents", contents);
            JsonObject generation = new JsonObject(); generation.addProperty("maxOutputTokens", settings.maxOutputTokens()); body.add("generationConfig", generation);
            if (request.actionsEnabled()) {
                JsonObject function = new JsonObject(); function.addProperty("name", ACTION_TOOL);
                function.addProperty("description", "Propose a command only for an explicit execution request; requires player confirmation.");
                function.add("parameters", actionSchema());
                JsonArray declarations = new JsonArray(); declarations.add(function);
                JsonObject tool = new JsonObject(); tool.add("functionDeclarations", declarations);
                JsonArray tools = new JsonArray(); tools.add(tool); body.add("tools", tools);
            }
        }
        return body.toString();
    }
    private static JsonArray textParts(String text) {
        JsonObject part = new JsonObject(); part.addProperty("text", text);
        JsonArray parts = new JsonArray(); parts.add(part); return parts;
    }

    public static AiResponse parseResponse(String provider, String json, int maxActions) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            StringBuilder text = new StringBuilder();
            List<AiResponse.Action> actions = new ArrayList<>();
            if (provider.equals("openai")) {
                JsonArray choices = root.getAsJsonArray("choices");
                if (choices == null || choices.isEmpty()) throw new IOException("模型没有返回有效候选结果。");
                JsonObject choice = choices.get(0).getAsJsonObject();
                if ("length".equals(string(choice, "finish_reason")) || "content_filter".equals(string(choice, "finish_reason")))
                    throw new IOException("模型回复被截断或拦截，请缩短问题。");
                JsonObject message = choice.getAsJsonObject("message");
                text.append(string(message, "content"));
                JsonArray calls = message.getAsJsonArray("tool_calls");
                if (calls != null) for (JsonElement element : calls) {
                    JsonObject call = element.getAsJsonObject();
                    if (!"function".equals(string(call, "type"))) continue;
                    JsonObject function = call.getAsJsonObject("function");
                    if (ACTION_TOOL.equals(string(function, "name")))
                        addAction(actions, JsonParser.parseString(string(function, "arguments")).getAsJsonObject(), maxActions);
                }
            } else {
                JsonArray candidates = root.getAsJsonArray("candidates");
                if (candidates == null || candidates.isEmpty()) throw new IOException("模型没有返回有效候选结果，可能被安全策略拦截。");
                JsonObject candidate = candidates.get(0).getAsJsonObject();
                String reason = string(candidate, "finishReason");
                if (!reason.isEmpty() && !reason.equals("STOP")) throw new IOException("模型回复未正常完成，请缩短问题或稍后再试。");
                JsonArray parts = candidate.getAsJsonObject("content").getAsJsonArray("parts");
                for (JsonElement element : parts) {
                    JsonObject part = element.getAsJsonObject();
                    if (!part.has("thought") || !part.get("thought").getAsBoolean()) text.append(string(part, "text"));
                    if (part.has("functionCall")) {
                        JsonObject call = part.getAsJsonObject("functionCall");
                        if (ACTION_TOOL.equals(string(call, "name"))) addAction(actions, call.getAsJsonObject("args"), maxActions);
                    }
                }
            }
            if (text.toString().isBlank() && actions.isEmpty()) throw new IOException("模型返回了空回复。");
            return new AiResponse(text.toString().strip(), actions);
        } catch (JsonParseException | IllegalStateException | NullPointerException | UnsupportedOperationException e) {
            throw new IOException("模型响应格式无效。");
        }
    }
    private static void addAction(List<AiResponse.Action> actions, JsonObject args, int max) throws IOException {
        if (actions.size() >= max) throw new IOException("模型提出的动作数量超过配置上限。");
        String command = string(args, "command"), reason = string(args, "reason");
        if (command.isBlank() || command.length() > 512 || reason.length() > 500) throw new IOException("模型动作参数无效。");
        actions.add(new AiResponse.Action(command, reason));
    }
    private static String string(JsonObject object, String name) {
        JsonElement e = object.get(name); return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
    @Override public void close() { client.shutdownNow(); }

    /** Cancels before buffering an unbounded body. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private final java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        private final int limit;
        private Flow.Subscription subscription;
        LimitedBody(int limit) { this.limit = limit; }
        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription s) { subscription = s; s.request(1); }
        @Override public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var b : buffers) {
                if ((long) output.size() + b.remaining() > limit) {
                    subscription.cancel(); result.completeExceptionally(new IOException("Response too large")); return;
                }
                byte[] bytes = new byte[b.remaining()]; b.get(bytes); output.writeBytes(bytes);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable t) { result.completeExceptionally(t); }
        @Override public void onComplete() { result.complete(output.toByteArray()); }
    }
}
