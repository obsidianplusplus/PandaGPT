package cn.panda.pandagpt.ai;

/**
 * 模型发出的一次工具调用。
 *
 * @param id 调用编号；服务商未提供时由插件生成，用于把结果对应回调用
 * @param arguments JSON 字符串形式的参数
 * @param signature Gemini 的思考签名，多轮调用时必须原样带回，其他服务商为 null
 */
public record ToolCall(String id, String name, String arguments, String signature) {
    /** 插件自行生成的调用编号前缀；这类编号不会回传给服务商。 */
    public static final String GENERATED_PREFIX = "panda_";
}
