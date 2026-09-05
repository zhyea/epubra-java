package com.epubra.app.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.logging.Filter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformLoggingTest {

    /**
     * 复现 JavaFX 那条告警：logger 名与运行时实测一致。
     *
     * <p>注意是 {@code javafx} 而不是 {@code com.sun.javafx.application.PlatformImpl}——
     * 后者只是 {@link LogRecord#getSourceClassName()} 显示的类名，logger 名是前者。
     */
    private static final String JAVAFX_LOGGER = PlatformLogging.JAVAFX_LOGGER_NAME;
    private static final String JAVAFX_INTERNAL_LOGGER =
            PlatformLogging.JAVAFX_INTERNAL_LOGGER_PREFIX + ".application.PlatformImpl";

    private Handler[] rootHandlers;
    private Filter[] savedFilters;

    @BeforeEach
    void rememberHandlers() {
        rootHandlers = Logger.getLogger("").getHandlers();
        savedFilters = new Filter[rootHandlers.length];
        for (int i = 0; i < rootHandlers.length; i++) {
            savedFilters[i] = rootHandlers[i].getFilter();
        }
    }

    @AfterEach
    void restoreHandlers() {
        for (int i = 0; i < rootHandlers.length; i++) {
            rootHandlers[i].setFilter(savedFilters[i]);
        }
    }

    private static LogRecord javaFxWarning() {
        LogRecord record = new LogRecord(Level.WARNING,
                PlatformLogging.UNSUPPORTED_CONFIG_MESSAGE + ": classes were loaded from 'unnamed module @4c610def'");
        record.setLoggerName(JAVAFX_LOGGER);
        return record;
    }

    private static LogRecord appWarning() {
        LogRecord record = new LogRecord(Level.WARNING, "书籍尚未保存");
        record.setLoggerName("com.epubra.app.controller");
        return record;
    }

    private static LogRecord otherJavaFxWarning() {
        LogRecord record = new LogRecord(Level.WARNING, "something else went wrong");
        record.setLoggerName(JAVAFX_LOGGER);
        return record;
    }

    @Test
    @DisplayName("识别 JavaFX 的 Unsupported JavaFX configuration 告警")
    void recognizesUnsupportedConfigWarning() {
        assertTrue(PlatformLogging.isUnsupportedConfigWarning(javaFxWarning()));
    }

    @Test
    @DisplayName("com.sun.javafx 前缀的 logger 同样识别")
    void recognizesInternalLoggerName() {
        LogRecord record = new LogRecord(Level.WARNING,
                PlatformLogging.UNSUPPORTED_CONFIG_MESSAGE + ": classes were loaded from 'unnamed module @1'");
        record.setLoggerName(JAVAFX_INTERNAL_LOGGER);

        assertTrue(PlatformLogging.isUnsupportedConfigWarning(record));
    }

    @Test
    @DisplayName("该告警的 logger 名不带 com.sun 前缀（回归防护，防改回错误判断）")
    void javaFxLoggerNameIsBareJavafx() {
        assertTrue(PlatformLogging.isJavaFxLogger("javafx"));
        assertTrue(PlatformLogging.isJavaFxLogger("com.sun.javafx.application.PlatformImpl"));
        assertFalse(PlatformLogging.isJavaFxLogger("com.epubra.app.controller"));
        assertFalse(PlatformLogging.isJavaFxLogger(null));
    }

    @Test
    @DisplayName("同前缀但不是那条告警的 JavaFX 日志不拦截")
    void leavesOtherJavaFxMessagesAlone() {
        assertFalse(PlatformLogging.isUnsupportedConfigWarning(otherJavaFxWarning()));
    }

    @Test
    @DisplayName("应用自身日志一律不拦截")
    void leavesAppMessagesAlone() {
        assertFalse(PlatformLogging.isUnsupportedConfigWarning(appWarning()));
    }

    @Test
    @DisplayName("quietJavaFx 后该告警被 root handler 的 filter 拦下")
    void quietJavaFxBlocksWarningAtHandler() {
        assumeRootHandler();

        PlatformLogging.quietJavaFx();

        for (Handler handler : Logger.getLogger("").getHandlers()) {
            assertNotNull(handler.getFilter());
            assertFalse(handler.getFilter().isLoggable(javaFxWarning()));
        }
    }

    @Test
    @DisplayName("quietJavaFx 后应用日志与其它 JavaFX 日志照常输出")
    void quietJavaFxKeepsOtherRecords() {
        assumeRootHandler();

        PlatformLogging.quietJavaFx();

        for (Handler handler : Logger.getLogger("").getHandlers()) {
            Filter filter = handler.getFilter();
            assertTrue(filter.isLoggable(appWarning()));
            assertTrue(filter.isLoggable(otherJavaFxWarning()));
        }
    }

    @Test
    @DisplayName("重复调用不叠加 filter（幂等）")
    void quietJavaFxIsIdempotent() {
        assumeRootHandler();

        PlatformLogging.quietJavaFx();
        Filter first = Logger.getLogger("").getHandlers()[0].getFilter();
        PlatformLogging.quietJavaFx();
        Filter second = Logger.getLogger("").getHandlers()[0].getFilter();

        assertSame(first, second);
    }

    /** root logger 至少有一个 handler 才有意义；没有则跳过（不因此让构建变红）。 */
    private static void assumeRootHandler() {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Logger.getLogger("").getHandlers().length > 0,
                "JUL root logger 没有 handler，跳过 handler 相关断言");
    }
}
