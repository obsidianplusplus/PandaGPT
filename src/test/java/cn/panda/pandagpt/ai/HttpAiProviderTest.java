package cn.panda.pandagpt.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.TestSupport;
import cn.panda.pandagpt.config.Provider;
import cn.panda.pandagpt.config.Settings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HttpAiProviderTest {
    private static Settings openai() {
        var config = TestSupport.config();
        config.set("panda.model_selection.provider", "openai");
        config.set("panda.model_selection.openai.model", "my-model");
        return Settings.load(config);
    }

    private static Settings provider(String id, Map<String, Object> overrides) {
        var config = TestSupport.config();
        config.set("panda.model_selection.provider", id);
        overrides.forEach(config::set);
        return Settings.load(config);
    }

    private static JsonObject tool(String name) {
        JsonObject function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("description", "test");
        function.add("parameters", JsonParser.parseString("{\"type\":\"object\",\"properties\":{}}"));
        return function;
    }

    /** 一段完整的多步对话：用户提问 → 模型调用两个工具 → 工具结果 → 附带图片的用户消息。 */
    private static AiRequest agentTurn(boolean finalAnswer) {
        var look = new ToolCall("call_1", "look", "{}", "sig-a");
        var status = new ToolCall(ToolCall.GENERATED_PREFIX + "x", "get_player_status", "", null);
        return new AiRequest(
                "system",
                List.of(
                        Message.user("old"),
                        new Message("assistant", "answer"),
                        Message.user("what is this?"),
                        Message.assistant("", List.of(look, status), null),
                        Message.tool(look, "region #1 FURNACE"),
                        Message.tool(status, "{\"health\":20}"),
                        Message.user("[image]", new AiRequest.Image("image/png", "dGVzdA=="))),
                List.of(tool("look"), tool("get_player_status")),
                finalAnswer);
    }

    @Test
    void semanticOutputBudgetIsSeparateFromChatAcrossAllWireFormats() {
        var semantic = new AiRequest("s", List.of(Message.user("evidence")), List.of(), true, 4096);
        var chat = new AiRequest("s", List.of(Message.user("chat")));
        for (String id : List.of("openai", "anthropic", "gemini")) {
            var settings = provider(id, Map.of("panda.max_output_tokens", 1000));
            var semanticBody = JsonParser.parseString(HttpAiProvider.buildBody(settings, semantic))
                    .getAsJsonObject();
            var chatBody = JsonParser.parseString(HttpAiProvider.buildBody(settings, chat))
                    .getAsJsonObject();
            int semanticTokens = id.equals("gemini")
                    ? semanticBody
                            .getAsJsonObject("generationConfig")
                            .get("maxOutputTokens")
                            .getAsInt()
                    : semanticBody
                            .get(
                                    id.equals("anthropic")
                                            ? "max_tokens"
                                            : settings.provider().maxTokensField())
                            .getAsInt();
            int chatTokens = id.equals("gemini")
                    ? chatBody.getAsJsonObject("generationConfig")
                            .get("maxOutputTokens")
                            .getAsInt()
                    : chatBody.get(
                                    id.equals("anthropic")
                                            ? "max_tokens"
                                            : settings.provider().maxTokensField())
                            .getAsInt();
            assertEquals(4096, semanticTokens);
            assertEquals(1000, chatTokens);
        }
        assertThrows(IllegalArgumentException.class, () -> new AiRequest("s", List.of(), List.of(), true, 8193));
    }

    @Test
    void openAiMultiStepToolConversationUsesNativeToolMessages() {
        var body = JsonParser.parseString(HttpAiProvider.buildBody(openai(), agentTurn(false)))
                .getAsJsonObject();
        assertEquals("my-model", body.get("model").getAsString());
        var messages = body.getAsJsonArray("messages");
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        var assistant = messages.get(4).getAsJsonObject();
        assertTrue(assistant.get("content").isJsonNull());
        assertEquals(2, assistant.getAsJsonArray("tool_calls").size());
        assertEquals(
                "look",
                assistant
                        .getAsJsonArray("tool_calls")
                        .get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("function")
                        .get("name")
                        .getAsString());
        var result = messages.get(5).getAsJsonObject();
        assertEquals("tool", result.get("role").getAsString());
        assertEquals("call_1", result.get("tool_call_id").getAsString());
        assertEquals(
                "data:image/png;base64,dGVzdA==",
                messages.get(7)
                        .getAsJsonObject()
                        .getAsJsonArray("content")
                        .get(1)
                        .getAsJsonObject()
                        .getAsJsonObject("image_url")
                        .get("url")
                        .getAsString());
        assertEquals(2, body.getAsJsonArray("tools").size());
        assertEquals("auto", body.get("tool_choice").getAsString());
        var last = JsonParser.parseString(HttpAiProvider.buildBody(openai(), agentTurn(true)))
                .getAsJsonObject();
        assertEquals("none", last.get("tool_choice").getAsString());
    }

    @Test
    void geminiEchoesThoughtSignaturesAndMergesFunctionResponsesWithTheImage() {
        var body = JsonParser.parseString(HttpAiProvider.buildBody(TestSupport.settings(), agentTurn(false)))
                .getAsJsonObject();
        var contents = body.getAsJsonArray("contents");
        // user, model, user, model(两个函数调用), user(两个函数结果 + 图片)
        assertEquals(5, contents.size());
        var calls = contents.get(3).getAsJsonObject().getAsJsonArray("parts");
        assertEquals(
                "sig-a", calls.get(0).getAsJsonObject().get("thoughtSignature").getAsString());
        assertEquals(
                "call_1",
                calls.get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("functionCall")
                        .get("id")
                        .getAsString());
        assertFalse(
                calls.get(1).getAsJsonObject().getAsJsonObject("functionCall").has("id"));
        var results = contents.get(4).getAsJsonObject();
        assertEquals("user", results.get("role").getAsString());
        var parts = results.getAsJsonArray("parts");
        assertEquals(
                "look",
                parts.get(0)
                        .getAsJsonObject()
                        .getAsJsonObject("functionResponse")
                        .get("name")
                        .getAsString());
        assertTrue(parts.get(3).getAsJsonObject().has("inlineData"));
        assertEquals(
                "AUTO",
                body.getAsJsonObject("toolConfig")
                        .getAsJsonObject("functionCallingConfig")
                        .get("mode")
                        .getAsString());
        var last = JsonParser.parseString(HttpAiProvider.buildBody(TestSupport.settings(), agentTurn(true)))
                .getAsJsonObject();
        assertEquals(
                "NONE",
                last.getAsJsonObject("toolConfig")
                        .getAsJsonObject("functionCallingConfig")
                        .get("mode")
                        .getAsString());
    }

    @Test
    void toolCallsAreParsedWithIdsArgumentsAndSignatures() throws Exception {
        AiResponse openai = HttpAiProvider.parseResponse("openai", """
                {"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[
                {"id":"call_9","type":"function","function":{"name":"locate_place","arguments":"{\\"name\\":\\"家\\"}"}}]}}]}
                """);
        assertEquals(List.of(new ToolCall("call_9", "locate_place", "{\"name\":\"家\"}", null)), openai.toolCalls());
        AiResponse gemini = HttpAiProvider.parseResponse("gemini", """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[
                {"text":"hidden","thought":true},{"text":"Let me look."},
                {"functionCall":{"name":"look","args":{"show_hits":true}},"thoughtSignature":"sig"}]}}]}
                """);
        assertEquals("Let me look.", gemini.text());
        assertEquals("look", gemini.toolCalls().getFirst().name());
        assertEquals("{\"show_hits\":true}", gemini.toolCalls().getFirst().arguments());
        assertEquals("sig", gemini.toolCalls().getFirst().signature());
        assertTrue(gemini.toolCalls().getFirst().id().startsWith(ToolCall.GENERATED_PREFIX));
    }

    @Test
    void anthropicMergesToolResultsIntoAlternatingUserMessages() {
        var body = JsonParser.parseString(HttpAiProvider.buildBody(provider("anthropic", Map.of()), agentTurn(false)))
                .getAsJsonObject();
        assertEquals("claude-sonnet-5-5", body.get("model").getAsString());
        assertEquals("system", body.get("system").getAsString());
        var messages = body.getAsJsonArray("messages");
        // user, assistant, user, assistant(两个 tool_use), user(两个 tool_result + 文字 + 图片)
        assertEquals(5, messages.size());
        var uses = messages.get(3).getAsJsonObject().getAsJsonArray("content");
        assertEquals("tool_use", uses.get(0).getAsJsonObject().get("type").getAsString());
        assertTrue(uses.get(1).getAsJsonObject().getAsJsonObject("input").isEmpty());
        var results = messages.get(4).getAsJsonObject().getAsJsonArray("content");
        assertEquals("tool_result", results.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals(
                "call_1", results.get(0).getAsJsonObject().get("tool_use_id").getAsString());
        assertEquals("image", results.get(3).getAsJsonObject().get("type").getAsString());
        var tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
        assertEquals("object", tool.getAsJsonObject("input_schema").get("type").getAsString());
        assertEquals("auto", body.getAsJsonObject("tool_choice").get("type").getAsString());
        var last = JsonParser.parseString(HttpAiProvider.buildBody(provider("anthropic", Map.of()), agentTurn(true)))
                .getAsJsonObject();
        assertEquals("none", last.getAsJsonObject("tool_choice").get("type").getAsString());
    }

    @Test
    void anthropicResponsesAreParsed() throws Exception {
        AiResponse result = HttpAiProvider.parseResponse(Provider.ANTHROPIC, """
                {"stop_reason":"tool_use","content":[{"type":"thinking","thinking":"x"},{"type":"text","text":"Checking."},
                {"type":"tool_use","id":"toolu_1","name":"look","input":{"show_hits":true}}]}
                """);
        assertEquals("Checking.", result.text());
        assertEquals(
                new ToolCall("toolu_1", "look", "{\"show_hits\":true}", null),
                result.toolCalls().getFirst());
        assertThrows(
                IOException.class,
                () -> HttpAiProvider.parseResponse(
                        Provider.ANTHROPIC, "{\"stop_reason\":\"max_tokens\",\"content\":[]}"));
    }

    @Test
    void compatibleProvidersUseTheirDefaultsAndDropImagesWithoutVision() {
        Settings deepseek = provider("deepseek", Map.of());
        assertEquals("https://api.deepseek.com/chat/completions", deepseek.apiUrl());
        assertFalse(deepseek.visionInput());
        var body = JsonParser.parseString(HttpAiProvider.buildBody(deepseek, agentTurn(false)))
                .getAsJsonObject();
        assertEquals("deepseek-chat", body.get("model").getAsString());
        assertEquals(1000, body.get("max_tokens").getAsInt());
        assertFalse(body.has("max_completion_tokens"));
        var image = body.getAsJsonArray("messages").get(7).getAsJsonObject().get("content");
        assertTrue(image.isJsonPrimitive());
        assertTrue(image.getAsString().contains("image omitted"));
        var withVision = JsonParser.parseString(HttpAiProvider.buildBody(
                        provider("deepseek", Map.of("panda.model_selection.deepseek.vision", true)), agentTurn(false)))
                .getAsJsonObject();
        assertTrue(withVision
                .getAsJsonArray("messages")
                .get(7)
                .getAsJsonObject()
                .get("content")
                .isJsonArray());
    }

    @Test
    void azureUsesApiKeyHeaderAndRequiresAnEndpoint() {
        assertThrows(IllegalArgumentException.class, () -> provider("azure", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> provider("custom", Map.of()));
        Settings azure = provider(
                "azure",
                Map.of(
                        "panda.model_selection.azure.api_url",
                        "https://res.openai.azure.com/openai/v1/chat/completions",
                        "panda.model_selection.azure.api_key",
                        "k"));
        var builder = HttpRequest.newBuilder(URI.create(azure.apiUrl()));
        WireFormat.of(azure.provider()).authorize(builder, azure);
        var headers = builder.GET().build().headers();
        assertEquals("k", headers.firstValue("api-key").orElseThrow());
        assertTrue(headers.firstValue("Authorization").isEmpty());
    }

    @Test
    void everyProviderIdParsesAndUnknownIdsListTheChoices() {
        for (Provider provider : Provider.values()) {
            assertEquals(provider, Provider.parse(provider.id().toUpperCase()));
        }
        assertThrows(IllegalArgumentException.class, () -> Provider.parse("unknown"));
        assertTrue(Provider.ids().contains("deepseek"));
    }

    @Test
    void explanatorySlashCommandsAreOnlyText() throws Exception {
        AiResponse result = HttpAiProvider.parseResponse("openai", """
                {"choices":[{"message":{"content":"Use /kill or /give @p stone 64"}}]}
                """);
        assertTrue(result.toolCalls().isEmpty());
        assertTrue(result.text().contains("/kill"));
    }

    @Test
    void malformedBlockedAndTruncatedResponsesFail() {
        for (String json : List.of(
                "{}",
                "{\"choices\":[]}",
                "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}",
                "{\"choices\":[{\"message\":{\"content\":\"\"}}]}")) {
            assertThrows(IOException.class, () -> HttpAiProvider.parseResponse("openai", json));
        }
        assertThrows(
                IOException.class,
                () -> HttpAiProvider.parseResponse("gemini", "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void httpErrorsAreClassifiedWithoutLeakingRawBodies() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("secret-response".getBytes());
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(response));
        var provider = new HttpAiProvider(TestSupport.settings(), client);
        IOException e = assertThrows(
                IOException.class, () -> provider.generate(new AiRequest("system", List.of(Message.user("hello")))));
        assertTrue(e.getMessage().contains("401"));
        assertFalse(e.getMessage().contains("secret-response"));
        verify(client, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void transientGatewayErrorsAreRetried() throws Exception {
        HttpClient client = mock(HttpClient.class);
        HttpResponse<byte[]> unavailable = mock(HttpResponse.class), ok = mock(HttpResponse.class);
        when(unavailable.statusCode()).thenReturn(503);
        when(ok.statusCode()).thenReturn(200);
        when(ok.body())
                .thenReturn("{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[{\"text\":\"hi\"}]}}]}"
                        .getBytes());
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(CompletableFuture.completedFuture(unavailable), CompletableFuture.completedFuture(ok));
        var provider = new HttpAiProvider(TestSupport.settings(), client);
        assertEquals(
                "hi",
                provider.generate(new AiRequest("system", List.of(Message.user("hello"))))
                        .text());
        verify(client, times(2)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void slowBodyIsCancelledAtTheWholeRequestDeadline() {
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> future = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(future);
        var config = TestSupport.config();
        config.set("panda.requests.timeout_seconds", 1);
        var provider = new HttpAiProvider(Settings.load(config), client);
        assertThrows(
                IOException.class, () -> provider.generate(new AiRequest("system", List.of(Message.user("hello")))));
        assertTrue(future.isCancelled());
    }
}
