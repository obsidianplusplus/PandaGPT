package cn.panda.pandagpt.util;

import cn.panda.pandagpt.config.Settings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.FileHandler;
import java.util.logging.Handler;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

/**
 * 线程安全、按大小轮转的 UTF-8 文件日志；原始对话内容默认不记录。
 *
 * <p>线程：写入在独立的单线程队列执行，调用方（包括主线程）从不进行磁盘 I/O；队列满时丢弃并计数。
 */
public final class PandaLogger implements AutoCloseable {
    private final Logger log;
    private final Settings settings;
    private final Handler handler;
    private final ThreadPoolExecutor writer;
    private final AtomicLong dropped = new AtomicLong();

    public PandaLogger(Path folder, Settings settings) throws IOException {
        this.settings = settings;
        log = Logger.getLogger("PandaGPT.file." + UUID.randomUUID());
        log.setUseParentHandlers(false);
        if (settings.loggingEnabled()) {
            handler = new FileHandler(
                    folder.resolve("panda-%g.log").toString(), settings.logBytes(), settings.logFiles(), true);
            handler.setEncoding("UTF-8");
            handler.setFormatter(new SimpleFormatter());
            log.addHandler(handler);
        } else {
            handler = null;
        }
        writer = new ThreadPoolExecutor(
                1,
                1,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(256),
                Thread.ofPlatform().daemon().name("pandagpt-log-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void event(UUID player, String event) {
        if (!settings.loggingEnabled()) {
            return;
        }
        String bounded = FormatUtil.truncate(event, 32768);
        try {
            writer.execute(() -> log.info("player=" + player + " " + sanitize(bounded)));
        } catch (RejectedExecutionException e) {
            // 绝不在调用线程上执行文件 I/O；队列满时只计数。
            dropped.incrementAndGet();
        }
    }

    public void conversation(UUID player, String question, String answer) {
        if (settings.logConversations()) {
            event(player, "question=" + question + " answer=" + answer);
        }
    }

    // 去掉换行防止伪造日志行，并遮盖密钥与代理密码。
    private String sanitize(String message) {
        String value = message.replace('\n', ' ').replace('\r', ' ');
        if (!settings.apiKey().isBlank()) {
            value = value.replace(settings.apiKey(), "[REDACTED]");
        }
        if (!settings.proxy().password().isBlank()) {
            value = value.replace(settings.proxy().password(), "[REDACTED]");
        }
        return value;
    }

    public long droppedEvents() {
        return dropped.get();
    }

    @Override
    public void close() {
        writer.shutdown();
        if (handler == null) {
            return;
        }
        // 关闭文件句柄本身也是磁盘 I/O，放到后台线程在队列写完后执行，避免阻塞停用与重载。
        Thread.ofPlatform().daemon().name("pandagpt-log-close").start(() -> {
            try {
                while (!writer.awaitTermination(1, TimeUnit.SECONDS)) {
                    // 等待写入线程排空队列。
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            log.removeHandler(handler);
            handler.close();
        });
    }
}
