package cn.panda.pandagpt.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** 构造工具参数 JSON Schema 与读取参数的辅助方法。 */
public final class Schema {
    /** 一个参数定义。 */
    public record Field(String name, JsonObject schema, boolean required) {}

    public static JsonObject object(Field... fields) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Field field : fields) {
            properties.add(field.name(), field.schema());
            if (field.required()) {
                required.add(field.name());
            }
        }
        schema.add("properties", properties);
        if (!required.isEmpty()) {
            schema.add("required", required);
        }
        return schema;
    }

    public static Field string(String name, String description, boolean required) {
        return new Field(name, typed("string", description), required);
    }

    public static Field integer(String name, String description, boolean required) {
        return new Field(name, typed("integer", description), required);
    }

    public static Field bool(String name, String description) {
        return new Field(name, typed("boolean", description), false);
    }

    public static Field choice(String name, String description, boolean required, String... values) {
        JsonObject schema = typed("string", description);
        JsonArray options = new JsonArray();
        for (String value : values) {
            options.add(value);
        }
        schema.add("enum", options);
        return new Field(name, schema, required);
    }

    /** 数组参数，元素结构由 items 指定。 */
    public static Field array(String name, String description, JsonObject items, boolean required) {
        JsonObject schema = typed("array", description);
        schema.add("items", items);
        return new Field(name, schema, required);
    }

    /** 由若干字段组成的对象结构，可用作数组元素。 */
    public static JsonObject shape(Field... fields) {
        return object(fields);
    }

    /** 整数数组参数，例如坐标 [x, y, z]。 */
    public static Field integers(String name, String description, boolean required) {
        JsonObject item = new JsonObject();
        item.addProperty("type", "integer");
        return array(name, description, item, required);
    }

    private static JsonObject typed(String type, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        schema.addProperty("description", description);
        return schema;
    }

    /** 读取字符串参数，缺失时返回空字符串。 */
    public static String text(JsonObject args, String key) {
        JsonElement e = args.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString().strip();
    }

    /** 读取整数参数；模型可能把数字写成字符串或小数。 */
    public static int number(JsonObject args, String key, int fallback) {
        JsonElement e = args.get(key);
        if (e == null || e.isJsonNull()) {
            return fallback;
        }
        try {
            double value = e.getAsDouble();
            return value == Math.rint(value) ? (int) value : Integer.MIN_VALUE;
        } catch (NumberFormatException | UnsupportedOperationException ex) {
            return Integer.MIN_VALUE;
        }
    }

    public static boolean flag(JsonObject args, String key) {
        JsonElement e = args.get(key);
        if (e == null || !e.isJsonPrimitive()) {
            return false;
        }
        return e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : "true".equalsIgnoreCase(e.getAsString());
    }

    private Schema() {}
}
