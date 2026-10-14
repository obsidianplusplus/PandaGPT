package cn.panda.pandagpt.ai;

import cn.panda.pandagpt.TestSupport;
import cn.panda.pandagpt.config.Settings;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.http.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HttpAiProviderTest {
    @Test void imageIsAttachedOnlyToTheCurrentUserTurnInBothProtocols() {
        var request = new AiRequest("system", List.of(new Message("user", "old"), new Message("assistant", "answer"),
                new Message("user", "look")), false, new AiRequest.Image("image/png", "dGVzdA=="));
        var config = TestSupport.config(); config.set("panda.model_selection.provider", "openai");
        var openai = JsonParser.parseString(HttpAiProvider.buildBody(Settings.load(config), request)).getAsJsonObject();
        var messages = openai.getAsJsonArray("messages");
        assertTrue(messages.get(1).getAsJsonObject().get("content").isJsonPrimitive());
        assertEquals("data:image/png;base64,dGVzdA==", messages.get(3).getAsJsonObject().getAsJsonArray("content")
                .get(1).getAsJsonObject().getAsJsonObject("image_url").get("url").getAsString());
        var gemini = JsonParser.parseString(HttpAiProvider.buildBody(TestSupport.settings(), request)).getAsJsonObject();
        var contents = gemini.getAsJsonArray("contents");
        assertEquals(1, contents.get(0).getAsJsonObject().getAsJsonArray("parts").size());
        assertEquals("image/png", contents.get(2).getAsJsonObject().getAsJsonArray("parts").get(1)
                .getAsJsonObject().getAsJsonObject("inlineData").get("mimeType").getAsString());
    }
    @Test void nativeRolesAndConfiguredModelAreTransmitted() {
        var config = TestSupport.config(); config.set("panda.model_selection.provider", "openai");
        config.set("panda.model_selection.openai.model", "my-model");
        var request = new AiRequest("system", List.of(new Message("user", "q1"), new Message("assistant", "a1"), new Message("user", "q2")), true);
        var body = JsonParser.parseString(HttpAiProvider.buildBody(Settings.load(config), request)).getAsJsonObject();
        assertEquals("my-model", body.get("model").getAsString());
        assertEquals("system", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("assistant", body.getAsJsonArray("messages").get(2).getAsJsonObject().get("role").getAsString());
        assertTrue(body.has("tools"));
        var gemini = JsonParser.parseString(HttpAiProvider.buildBody(TestSupport.settings(), request)).getAsJsonObject();
        assertTrue(gemini.has("systemInstruction"));
        assertEquals("model", gemini.getAsJsonArray("contents").get(1).getAsJsonObject().get("role").getAsString());
    }
    @Test void explanatorySlashCommandsAreNeverActions() throws Exception {
        AiResponse result = HttpAiProvider.parseResponse("openai", """
                {"choices":[{"message":{"content":"Use /kill or /give @p stone 64"}}]}
                """, 3);
        assertTrue(result.actions().isEmpty());
        assertTrue(result.text().contains("/kill"));
    }
    @Test void structuredToolArgumentsArePreservedAndGeminiPartsAreCombined() throws Exception {
        AiResponse openai = HttpAiProvider.parseResponse("openai", """
                {"choices":[{"message":{"content":null,"tool_calls":[{"type":"function","function":{"name":"propose_player_command","arguments":"{\\"command\\":\\"tp -10.5 64 20\\",\\"reason\\":\\"requested\\"}"}}]}}]}
                """, 3);
        assertEquals("tp -10.5 64 20", openai.actions().getFirst().command());
        AiResponse gemini = HttpAiProvider.parseResponse("gemini", """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":"hidden","thought":true},{"text":"a"},{"text":"b"},{"functionCall":{"name":"propose_player_command","args":{"command":"help","reason":"requested"}}}]}}]}
                """, 3);
        assertEquals("ab", gemini.text()); assertEquals(1, gemini.actions().size());
    }
    @Test void malformedBlockedAndTruncatedResponsesFailWithoutExecutingAnything() {
        for (String json : List.of("{}", "{\"choices\":[]}", "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}"))
            assertThrows(IOException.class, () -> HttpAiProvider.parseResponse("openai", json, 3));
        assertThrows(IOException.class, () -> HttpAiProvider.parseResponse("gemini", "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}", 3));
        assertThrows(IOException.class, () -> HttpAiProvider.parseResponse("openai", """
                {"choices":[{"message":{"tool_calls":[{"type":"function","function":{"name":"propose_player_command","arguments":"{}"}}]}}]}
                """, 3));
    }
    @Test @SuppressWarnings("unchecked") void httpErrorsAreClassifiedWithoutLeakingRawBodies() throws Exception {
        HttpClient client = mock(HttpClient.class); HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401); when(response.body()).thenReturn("secret-response".getBytes());
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
        var provider = new HttpAiProvider(TestSupport.settings(), client);
        IOException e = assertThrows(IOException.class, () -> provider.generate(new AiRequest("system", List.of(new Message("user", "hello")), false)));
        assertTrue(e.getMessage().contains("401")); assertFalse(e.getMessage().contains("secret-response"));
    }
    @Test @SuppressWarnings("unchecked") void slowBodyIsCancelledAtTheWholeRequestDeadline() throws Exception {
        HttpClient client = mock(HttpClient.class);
        CompletableFuture<HttpResponse<byte[]>> future = new CompletableFuture<>();
        when(client.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(future);
        var config = TestSupport.config(); config.set("panda.requests.timeout_seconds", 1);
        var provider = new HttpAiProvider(Settings.load(config), client);
        assertThrows(IOException.class, () -> provider.generate(new AiRequest("system", List.of(new Message("user", "hello")), false)));
        assertTrue(future.isCancelled());
    }
}
