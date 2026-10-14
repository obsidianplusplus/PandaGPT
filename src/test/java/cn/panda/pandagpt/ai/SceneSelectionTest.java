package cn.panda.pandagpt.ai;

import cn.panda.pandagpt.*;
import cn.panda.pandagpt.config.Settings;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SceneSelectionTest {
    @Test void sceneSelectionSchemaContainsOnlyCurrentIdsForBothProviders() {
        var request = new AiRequest("scene", List.of(new Message("user", "左边那个")), false, null, List.of(1, 4));
        var config = TestSupport.config();
        for (String provider : List.of("openai", "gemini")) {
            config.set("panda.model_selection.provider", provider);
            JsonObject body = JsonParser.parseString(HttpAiProvider.buildBody(Settings.load(config), request)).getAsJsonObject();
            JsonObject tool = body.getAsJsonArray("tools").get(0).getAsJsonObject();
            JsonObject fn = provider.equals("openai") ? tool.getAsJsonObject("function") : tool.getAsJsonArray("functionDeclarations").get(0).getAsJsonObject();
            assertEquals("focus_scene_object", fn.get("name").getAsString());
            assertEquals("[\"1\",\"4\"]", fn.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("object_id").get("enum").toString());
            assertEquals("string", fn.getAsJsonObject("parameters").getAsJsonObject("properties").getAsJsonObject("object_id").get("type").getAsString());
        }
    }
    @Test void parsesToolOnlySelectionAndRejectsFractionalOrMultipleSelections() throws Exception {
        String response = """
                {"choices":[{"finish_reason":"stop","message":{"content":null,"tool_calls":[{"type":"function",
                "function":{"name":"focus_scene_object","arguments":"{\\"object_id\\":4}"}}]}}]}
                """;
        assertEquals(4, HttpAiProvider.parseResponse("openai", response, 3).selectedObject());
        var duplicate = JsonParser.parseString(response).getAsJsonObject();
        var calls = duplicate.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").getAsJsonArray("tool_calls");
        calls.add(calls.get(0).deepCopy());
        assertThrows(IOException.class, () -> HttpAiProvider.parseResponse("openai", duplicate.toString(), 3));
        assertThrows(IOException.class, () -> HttpAiProvider.parseResponse("openai", response.replace(":4}", ":4.5}"), 3));
        String gemini = """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"focus_scene_object","args":{"object_id":1}}}]}}]}
                """;
        assertEquals(1, HttpAiProvider.parseResponse("gemini", gemini, 3).selectedObject());
        assertThrows(IOException.class, () -> HttpAiProvider.parseResponse("gemini", gemini.replace("\"object_id\":1", "\"object_id\":49"), 3));
    }
    @Test void selectionToolCarriesTheAnswerWithoutASecondModelRequest() throws Exception {
        String response = """
                {"candidates":[{"finishReason":"STOP","content":{"parts":[{"functionCall":{"name":"focus_scene_object",
                "args":{"object_id":"2","answer":"把燃料和待烧炼物品放入所选熔炉。"}}}]}}]}
                """;
        var parsed = HttpAiProvider.parseResponse("gemini", response, 3);
        assertEquals(2, parsed.selectedObject()); assertTrue(parsed.text().contains("燃料"));
    }
}
