package cn.panda.pandagpt.util;

import cn.panda.pandagpt.config.Settings;
import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.*;

/** Thread-safe rotating UTF-8 logging; raw conversation logging is opt-in. */
public final class PandaLogger implements AutoCloseable {
    private final Logger log;
    private final Settings settings;
    private final Handler handler;
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
    }
    public void event(UUID player, String event) {
        if (settings.loggingEnabled()) log.info("player=" + player + " " + sanitize(event));
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
    @Override public void close() { if (handler != null) { log.removeHandler(handler); handler.close(); } }
}
