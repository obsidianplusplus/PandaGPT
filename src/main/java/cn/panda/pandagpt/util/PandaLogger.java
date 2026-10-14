package cn.panda.pandagpt.util;

import cn.panda.pandagpt.config.Settings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.*;

/** Thread-safe rotating UTF-8 logging; raw conversation logging is opt-in. */
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
            handler = new FileHandler(folder.resolve("panda-%g.log").toString(), settings.logBytes(), settings.logFiles(), true);
            handler.setEncoding("UTF-8");
            handler.setFormatter(new SimpleFormatter());
            log.addHandler(handler);
        } else handler = null;
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(256),
                Thread.ofPlatform().daemon().name("pandagpt-log-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    public void event(UUID player, String event) {
        if (!settings.loggingEnabled()) return;
        String bounded = event.length() > 32768 ? event.substring(0, 32768) : event;
        try { writer.execute(() -> log.info("player=" + player + " " + sanitize(bounded))); }
        catch (RejectedExecutionException e) { dropped.incrementAndGet(); } // Never run file I/O on the calling tick thread.
    }
    public void conversation(UUID player, String question, String answer) {
        if (settings.logConversations()) event(player, "question=" + question + " answer=" + answer);
    }
    private String sanitize(String message) {
        String value = message.replace('\n', ' ').replace('\r', ' ');
        if (!settings.apiKey().isBlank()) value = value.replace(settings.apiKey(), "[REDACTED]");
        if (!settings.proxy().password().isBlank()) value = value.replace(settings.proxy().password(), "[REDACTED]");
        return value;
    }
    public long droppedEvents() { return dropped.get(); }
    @Override public void close() {
        writer.shutdown();
        // Closing the handler is itself disk I/O. Execute it after queued records on the writer.
        if (handler != null) Thread.ofPlatform().daemon().name("pandagpt-log-close").start(() -> {
            try { while (!writer.awaitTermination(1, TimeUnit.SECONDS)) { /* Worker drains without blocking disable/reload. */ } }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            log.removeHandler(handler); handler.close();
        });
    }
}
