package cn.panda.pandagpt.agent;

import cn.panda.pandagpt.i18n.Msg;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import org.bukkit.entity.Player;

/**
 * Agent 可调用的一个工具。
 *
 * <p>线程：{@link #available} 与 {@link #call} 都在服务器主线程执行；需要等待多个 tick 的工具返回稍后完成的 future。
 */
public interface AgentTool {
    String name();

    /** 给模型看的用途说明（英文，便于模型理解）。 */
    String description();

    /** 参数的 JSON Schema。 */
    JsonObject parameters();

    /** 该玩家当前能否使用此工具，例如权限或模块开关。 */
    default boolean available(Player player) {
        return true;
    }

    /** 执行期间显示在玩家动作栏上的提示，可为 null。 */
    default Msg activity() {
        return null;
    }

    CompletableFuture<ToolResult> call(ToolContext context, JsonObject args);

    /** 函数声明，供线格式层发送给模型。 */
    default JsonObject declaration() {
        JsonObject function = new JsonObject();
        function.addProperty("name", name());
        function.addProperty("description", description());
        function.add("parameters", parameters());
        return function;
    }

    /** 同步工具的便捷构造。 */
    static AgentTool of(
            String name,
            String description,
            JsonObject parameters,
            Predicate<Player> available,
            Msg activity,
            BiFunction<ToolContext, JsonObject, ToolResult> body) {
        return async(
                name,
                description,
                parameters,
                available,
                activity,
                (context, args) -> CompletableFuture.completedFuture(body.apply(context, args)));
    }

    /** 异步工具的便捷构造。 */
    static AgentTool async(
            String name,
            String description,
            JsonObject parameters,
            Predicate<Player> available,
            Msg activity,
            BiFunction<ToolContext, JsonObject, CompletableFuture<ToolResult>> body) {
        return new AgentTool() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject parameters() {
                return parameters;
            }

            @Override
            public boolean available(Player player) {
                return available.test(player);
            }

            @Override
            public Msg activity() {
                return activity;
            }

            @Override
            public CompletableFuture<ToolResult> call(ToolContext context, JsonObject args) {
                return body.apply(context, args);
            }
        };
    }
}
