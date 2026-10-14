package cn.panda.pandagpt.i18n;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 延迟渲染的本地化消息：只保存消息键与参数，确定接收者语言后再格式化。
 *
 * <p>参数可以嵌套另一个 {@link Msg}，渲染时使用同一种语言。线程：不可变，可跨线程传递。
 */
public record Msg(String key, List<Object> args) {
    public Msg {
        args = Collections.unmodifiableList(new ArrayList<>(args));
    }

    public static Msg of(String key, Object... args) {
        return new Msg(key, Arrays.asList(args));
    }

    /** 按指定语言渲染。 */
    public String render(String language) {
        return Messages.active().format(language, this);
    }

    /** 按服务器默认语言渲染，用于日志与异常信息。 */
    @Override
    public String toString() {
        return Messages.active().format(Messages.active().defaultLanguage(), this);
    }
}
