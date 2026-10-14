package cn.panda.pandagpt.i18n;

/** 携带本地化消息的异常，展示给玩家时按其语言渲染，记录日志时按服务器默认语言渲染。 */
public interface Localized {
    Msg localized();
}
