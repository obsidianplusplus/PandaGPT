package cn.panda.pandagpt.ai;
public interface AiProvider extends AutoCloseable {
    AiResponse generate(AiRequest request) throws Exception;
    @Override default void close() {}
}
