package cn.panda.pandagpt.config;

import cn.panda.pandagpt.i18n.LocalizedException;
import java.util.Locale;

/** 支持的模型服务商；{@link #id()} 与配置文件中的写法一致。 */
public enum Provider {
    OPENAI("openai"),
    GEMINI("gemini");

    private final String id;

    Provider(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static Provider parse(String value) {
        String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
        for (Provider provider : values()) {
            if (provider.id.equals(normalized)) {
                return provider;
            }
        }
        throw new LocalizedException("config.provider_invalid");
    }

    @Override
    public String toString() {
        return id;
    }
}
