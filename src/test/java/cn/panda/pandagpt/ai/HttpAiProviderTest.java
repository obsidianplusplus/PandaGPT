package cn.panda.pandagpt.ai;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import cn.panda.pandagpt.TestSupport;
import cn.panda.pandagpt.config.Settings;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.http.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class HttpAiProviderTest {
    private static Settings openai() {
        var config = TestSupport.config();
        config.set("panda.model_selection.provider", "openai");
        config.set("panda.model_selection.openai.model", "my-model");
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
