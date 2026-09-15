package org.chobit.epubra.app.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.controller.view.TocController;
import org.chobit.epubra.app.platform.PreferenceNodes;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 格式命令降级路径的守卫：**可视化页签在前台时，格式命令绝不碰源码区**。
 *
 * <p>事故（2026-09-15）：章节刚加载进 WebView、selection 还为空时按 Ctrl+U，
 * {@code toggleInline} 返回 false，旧代码的 fallback 直接往隐藏的源码 TextArea 的
 * 陈旧光标处插 {@code <u></u>}——落点恰在 XML 声明里，书保存后解析报
 * 「[Fatal Error] :1:46 编码名称 "UTF-8&lt;u&gt;&lt;/u&gt;" 无效」。
 *
 * <p>修复口径（{@code MainController.applyFormatOrFallback}）：只有源码页签在前台
 * 才允许插片段；可视化页签在前台而编辑器未就绪时状态栏提示。本类钉死两个方向：
 * 前台是可视化页签时源码区一字不动；前台是源码页签时降级路径仍然可用。
 */
class FormatFallbackGuardTest {

    private static MainController mainController;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static Path userDataDir;

    @BeforeAll
    static void bootFxAndLoadMainWindow() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        // 会触发章节加载（预览镜像 / WebView 缓存落盘），用户数据根指到临时目录
        userDataDir = Files.createTempDirectory("epubra-format-fallback-guard");
        System.setProperty("epubra.userDataDir", userDataDir.toString());
        // 加载 FXML 会读 Preferences（最近工作空间），隔离掉开发者本机的真实注册表
        PreferenceNodes.useInMemoryForTesting();

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    FormatFallbackGuardTest.class.getResource(
                            "/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
        });
    }

    @AfterAll
    static void tearDown() {
        PreferenceNodes.resetForTesting();
        System.clearProperty("epubra.userDataDir");
    }

    @Test
    @Timeout(90)
    @DisplayName("可视化页签在前台、编辑器未就绪时按 Ctrl+U：源码区一字不动")
    void underlineOnVisualTabNeverTouchesHiddenSourceArea() throws Exception {
        AtomicReference<String> before = new AtomicReference<>();
        runOnFx(() -> {
            BookContext ctx = field(mainController, "ctx");
            TocController tocController = field(mainController, "tocViewController");
            TextArea sourceArea = field(mainController, "contentArea");
            TabPane tabs = field(mainController, "editorTabs");
            Book book = BookFactory.createEmpty("fallback 守卫·可视化页签");
            ctx.setBook(book);
            Resource chapter = book.spineResources().get(0);
            tabs.getSelectionModel().select(1);
            tocController.setCurrentNode(new ChapterNode("第一章", chapter, null));
            sourceArea.setText(chapter.asString());
            sourceArea.positionCaret(0);
            // 切到可视化页签会触发 reload（loadContent 异步，此刻必然未就绪）——
            // 同一 FX 脉冲内立即按 Ctrl+U，正是事故现场
            tabs.getSelectionModel().select(0);
            mainController.onInsertUnderline();
            before.set(sourceArea.getText());
        });
        // 内容必须一字不动；若 fallback 仍插了 <u></u>，这句话就会精确变红
        assertTrue(before.get() != null && !before.get().startsWith("<u></u>"),
                "可视化页签在前台时绝不能往隐藏的源码区插片段");
        assertEquals(-1, before.get().indexOf("<u></u>"),
                "源码区任何位置都不应出现 <u></u>");
    }

    @Test
    @Timeout(90)
    @DisplayName("源码页签在前台时按 Ctrl+U：降级路径仍可用（在光标处插 <u></u>）")
    void underlineOnSourceTabStillFallsBackToFragmentInsert() throws Exception {
        runOnFx(() -> {
            BookContext ctx = field(mainController, "ctx");
            TocController tocController = field(mainController, "tocViewController");
            TextArea sourceArea = field(mainController, "contentArea");
            TabPane tabs = field(mainController, "editorTabs");
            Book book = BookFactory.createEmpty("fallback 守卫·源码页签");
            ctx.setBook(book);
            Resource chapter = book.spineResources().get(0);
            tocController.setCurrentNode(new ChapterNode("第一章", chapter, null));
            tabs.getSelectionModel().select(1);
            sourceArea.setText(chapter.asString());
            sourceArea.positionCaret(0);
            mainController.onInsertUnderline();
        });
        AtomicReference<String> after = new AtomicReference<>();
        runOnFx(() -> after.set(((TextArea) field(mainController, "contentArea")).getText()));
        assertTrue(after.get().startsWith("<u></u>"),
                "源码页签在前台时降级路径必须仍然可用（否则功能零丢失被破坏）");
    }

    // ------------------------------------------------------------------ 助手

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return (T) f.get(target);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }

    private static void runOnFx(FxTask r) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                r.runWithException();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "FX 任务超时");
        if (err.get() != null) {
            throw new RuntimeException("FX task failed: " + err.get().getMessage(), err.get());
        }
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}
