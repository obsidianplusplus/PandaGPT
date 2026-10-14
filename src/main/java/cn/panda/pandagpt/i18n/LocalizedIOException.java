package cn.panda.pandagpt.i18n;

import java.io.IOException;

/** 网络、文件或模型响应错误；消息内容已脱敏，可以直接展示给玩家。 */
public final class LocalizedIOException extends IOException implements Localized {
    private final transient Msg message;

    public LocalizedIOException(String key, Object... args) {
        super(key);
        message = Msg.of(key, args);
    }

    @Override
    public Msg localized() {
        return message;
    }

    @Override
    public String getMessage() {
        return message.toString();
    }
}
