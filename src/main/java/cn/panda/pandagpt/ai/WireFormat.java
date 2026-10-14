package cn.panda.pandagpt.ai;

import cn.panda.pandagpt.config.Provider;
import cn.panda.pandagpt.config.Settings;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;

/** 某个模型服务商的请求/响应线格式。实现必须无状态，可被多个工作线程并发调用。 */
interface WireFormat {
    URI uri(Settings settings);

    void authorize(HttpRequest.Builder builder, Settings settings);

    String body(Settings settings, AiRequest request);

    AiResponse parse(String json) throws IOException;

    static WireFormat of(Provider provider) {
        return switch (provider) {
            case OPENAI -> OpenAiFormat.INSTANCE;
            case GEMINI -> GeminiFormat.INSTANCE;
        };
    }

    /** 读取字符串字段；缺失或 null 视为空字符串。 */
    static String string(JsonObject object, String name) {
        JsonElement e = object == null ? null : object.get(name);
        return e == null || e.isJsonNull() ? "" : e.isJsonPrimitive() ? e.getAsString() : e.toString();
    }
}
