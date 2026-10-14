package cn.panda.pandagpt.ai;

/** 模型服务抽象；实现会在 AI 工作线程上被调用，不得访问 Bukkit 对象。 */
public interface AiProvider extends AutoCloseable {
    AiResponse generate(AiRequest request) throws Exception;

    @Override
    default void close() {}
}
