package com.epubra.app.support;

import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * 启动期日志降噪。
 *
 * <p>本项目是非模块化构建（无 {@code module-info.java}），JavaFX 只出现在 classpath。
 * 这条设计决定带来一条无法用 JVM 参数消除的启动告警——JavaFX 自身在检测到从
 * {@code unnamed module} 加载时会打：
 * <pre>
 * 警告: Unsupported JavaFX configuration: classes were loaded from 'unnamed module @...'
 * </pre>
 *
 * <p>为什么用 Handler Filter 而不是 {@code setLevel}：JavaFX 经由 {@code System.Logger}
 * （JEP 264）桥接到 JUL，桥接层对具体 logger 的 level 处理不遵循常规的父子继承，
 * 实测把 {@code com.sun.javafx} 设为 {@code Level.OFF} 后该告警照旧输出。
 * 改在 root logger 的 {@link Handler} 上挂 {@link Filter}：不管 logger 层级如何，
 * 最终要打到控制台的记录都会过这一关，可按 logger 名与消息内容精准拦截。
 *
 * <p>只拦截这一条已知的架构性噪音，JavaFX 其它告警与应用自身日志照常输出。
 * 剩余两条 native 相关告警（{@code System::load} 与 Marlin 的 {@code Unsafe::allocateMemory}）
 * 属于 JVM 层，只能在启动时用 {@code --enable-native-access} 与
 * {@code --sun-misc-unsafe-memory-access=allow} 处理，代码无法代劳。
 */
public final class PlatformLogging {

    /**
     * JavaFX 内部日志用的 logger 名。
     *
     * <p>实测是 {@code "javafx"}——注意不是 {@code com.sun.javafx.*}：后者只是
     * {@link LogRecord#getSourceClassName()} 显示的类名，二者是两回事。
     */
    public static final String JAVAFX_LOGGER_NAME = "javafx";

    /** 兼容写法：部分 JavaFX 内部 logger 仍以完整包名注册。 */
    public static final String JAVAFX_INTERNAL_LOGGER_PREFIX = "com.sun.javafx";

    /** 要拦截的告警正文前缀（JavaFX 源码里的原文，版本升级时需核对）。 */
    public static final String UNSUPPORTED_CONFIG_MESSAGE = "Unsupported JavaFX configuration";

    private PlatformLogging() {
    }

    /**
     * 屏蔽非模块化运行方式带来的 JavaFX 配置告警。
     *
     * <p>必须在 {@code Application.launch()}（其内部才会触发该告警）之前调用。
     * 幂等：重复调用不会叠加 filter。
     */
    public static void quietJavaFx() {
        for (Handler handler : Logger.getLogger("").getHandlers()) {
            if (handler.getFilter() == UNSUPPORTED_CONFIG_FILTER) {
                continue;
            }
            Filter existing = handler.getFilter();
            handler.setFilter(existing == null
                    ? UNSUPPORTED_CONFIG_FILTER
                    : record -> existing.isLoggable(record) && !isUnsupportedConfigWarning(record));
        }
    }

    /** 精确匹配那一条告警：限定 JavaFX 来源 + 消息前缀，避免误伤同名业务日志。 */
    public static boolean isUnsupportedConfigWarning(LogRecord record) {
        if (record == null || record.getMessage() == null) {
            return false;
        }
        return isJavaFxLogger(record.getLoggerName())
                && record.getMessage().startsWith(UNSUPPORTED_CONFIG_MESSAGE);
    }

    /** JavaFX 内部日志以 {@code javafx} 或 {@code com.sun.javafx} 注册，两种都认。 */
    public static boolean isJavaFxLogger(String loggerName) {
        return loggerName != null
                && (loggerName.startsWith(JAVAFX_LOGGER_NAME)
                || loggerName.startsWith(JAVAFX_INTERNAL_LOGGER_PREFIX));
    }

    private static final Filter UNSUPPORTED_CONFIG_FILTER =
            record -> !isUnsupportedConfigWarning(record);
}
