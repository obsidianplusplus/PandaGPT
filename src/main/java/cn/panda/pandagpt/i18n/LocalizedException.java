package cn.panda.pandagpt.i18n;

/** 配置或参数校验失败；消息内容不包含密钥、URL 等敏感底层信息。 */
public final class LocalizedException extends IllegalArgumentException implements Localized {
    private final transient Msg message;

    public LocalizedException(String key, Object... args) {
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
