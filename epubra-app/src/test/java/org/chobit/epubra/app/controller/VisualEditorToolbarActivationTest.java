package org.chobit.epubra.app.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.controller.view.TocController;
import org.chobit.epubra.app.editor.VisualEditorSession;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具条激活态的全链路契约（#54）：真实 FXML + 真实 MainController + 真实 Java 桥。
 *
 * <p>#54 用户实测：把文字设为斜体后，重新选中文字 / 光标落在斜体文字上，
 * 工具条「斜体」按钮不点亮。WebView 层（epubraQuery / notifySelection / stub 桥）
 * 已由 {@code VisualEditorInlineFormatTest} 证明正常，本类把<b>真实 Java 桥</b>与
 * <b>updateToolbarState → styleClass</b> 这段接进链路，钉死行为：
 *
 * <ol>
 *   <li>选中文字点「斜体」→ 按钮点亮；</li>
 *   <li>光标落回斜体文字（collapsed 选区）→ 按钮仍点亮；</li>
 *   <li>重新拖选斜体文字 → 按钮仍点亮；</li>
 *   <li>光标移出斜体 → 按钮熄灭；</li>
 *   <li>再点一次「斜体」→ 取消斜体，按钮熄灭。</li>
 * </ol>
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@link StatusProgressUiTest} 的说明。
 */
class VisualEditorToolbarActivationTest {

    private static MainController mainController;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

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
        userDataDir = java.nio.file.Files.createTempDirectory("epubra-toolbar-activation-test");
        System.setProperty("epubra.userDataDir", userDataDir.toString());

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    VisualEditorToolbarActivationTest.class.getResource(
                            "/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
        });
    }

    @AfterAll
    static void releaseUserDataDir() {
        System.clearProperty("epubra.userDataDir");
    }

    private static java.nio.file.Path userDataDir;

    @Test
    @Timeout(90)
    @DisplayName("斜体激活态全链路：点亮 → 光标落回仍亮 → 拖选仍亮 → 移出熄灭 → 再点取消")
    void italicToolbarActivatesWhenSelectionReentersItalicText() throws Exception {
        // ---- 开书、选中章节、切到编辑 tab，等可视化编辑器真正可用 ----------------
        // 编辑 tab 是默认选中项：必须先切到源码 tab 再切回来，「切换」事件才会触发加载。
        // setCurrentNode 只记状态不触发 showChapter（真实用户点树才有），源码区要自己装载——
        // 否则切回编辑 tab 时 flushCurrentChapter 会把空 contentArea 写进章节资源，编辑器拿到空 body。
        runOnFx(() -> {
            BookContext ctx = field(mainController, "ctx");
            TocController tocController = field(mainController, "tocViewController");
            javafx.scene.control.TextArea sourceArea = field(mainController, "contentArea");
            Book book = BookFactory.createEmpty("激活态测试");
            ctx.setBook(book);
            org.chobit.epubra.lib.domain.Resource chapter = book.spineResources().get(0);
            TabPane tabs = field(mainController, "editorTabs");
            tabs.getSelectionModel().select(1);
            tocController.setCurrentNode(new ChapterNode("第一章", chapter, null));
            sourceArea.setText(chapter.asString());
            tabs.getSelectionModel().select(0);
        });
        awaitVisualEditorUsable();

        // ---- 选中段落文字，点工具条「斜体」--------------------------------------
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " var r = document.createRange(); r.selectNodeContents(p);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        runOnFx(() -> mainController.onInsertItalic());
        // selectionchange 是异步派发的，FX 事件循环里才跑；mouseup 监听器同步执行，
        // 用它确定性地驱动一次上报——若这样都点不亮，说明真实 Java 桥断了
        runScript("document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}))");

        assertTrue(toolbarButtonActive("italic"),
                "点「斜体」后按钮应点亮（epubraFormat 改选区 → 选区上报 italic）");

        // ---- 用户场景核心：收起选区，再把光标落回斜体文字上 → 必须保持点亮 -------
        runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));"
                + " return true; })()");
        assertTrue(toolbarButtonActive("italic"),
                "光标落在斜体文字上时按钮必须点亮——#54 用户实测不亮的场景");

        // ---- 重新拖选斜体文字 → 仍点亮 ------------------------------------------
        runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));"
                + " return true; })()");
        assertTrue(toolbarButtonActive("italic"),
                "重新选中斜体文字时按钮必须点亮");

        // ---- 整段选中（起点在包裹外，三击选段形态）→ 仍点亮 -----------------------
        runScript("(function () {"
                + " var p = document.body.querySelector('p');"
                + " var r = document.createRange(); r.selectNodeContents(p);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));"
                + " return true; })()");
        assertTrue(toolbarButtonActive("italic"),
                "整段选中完整覆盖斜体包裹时按钮必须点亮——#54 用户实测「把斜体字选中」的形态");

        // ---- 光标移出斜体范围 → 熄灭 --------------------------------------------
        // 模板只有一个段落（整段已是 em），改用「点进非斜体的标题」模拟移出
        runScript("(function () {"
                + " var h = document.body.querySelector('h1');"
                + " var r = document.createRange(); r.selectNodeContents(h); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}));"
                + " return true; })()");
        assertFalse(toolbarButtonActive("italic"),
                "光标移出斜体文字后按钮应熄灭");

        // ---- 再点一次「斜体」→ toggle off ----------------------------------------
        // 光标已离开斜体，先把光标放回斜体文字内再点按钮（与用户操作一致）
        runScript("(function () {"
                + " var em = document.body.querySelector('em');"
                + " var r = document.createRange(); r.selectNodeContents(em); r.collapse(true);"
                + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                + " return true; })()");
        runOnFx(() -> mainController.onInsertItalic());
        // 取消也是一次选区变化：unwrapCovered 恢复的选区经异步 selectionchange 上报，
        // 用同步 mouseup 确定性驱动一次
        runScript("document.dispatchEvent(new MouseEvent('mouseup', {bubbles: true}))");

        Object emCount = runScript("document.body.querySelectorAll('em').length");
        assertEqualsInt(0, emCount, "斜体内再点一次「斜体」应取消斜体（em 清除）");
        assertFalse(toolbarButtonActive("italic"), "取消斜体后按钮应熄灭");
    }

    // ------------------------------------------------------------------ 助手

    /** 等可视化编辑器真正可用：loaded 标记 + 页面脚本 + Java 桥三者都就位。 */
    private static void awaitVisualEditorUsable() throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            runOnFx(() -> {
                // loaded 标记已随会话迁往 VisualEditorSession（拆分批次 B）——它必须与
                // JS 桥、序列化回写同处一地，所以这里从会话上读，而不是主控制器的字段
                VisualEditorSession session = field(mainController, "visualEditorSession");
                if (session != null && session.isLoaded()) {
                    Object format = mainControllerRun("typeof window.epubraFormat === 'function'");
                    Object bridge = mainControllerRun("!!window.epubraBridge");
                    ready.set(Boolean.TRUE.equals(format) && Boolean.TRUE.equals(bridge));
                }
            });
            if (ready.get()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("可视化编辑器 30s 内未就绪（epubraFormat / epubraBridge 缺一不可）");
    }

    /** 在主控制器持有的可视化 WebView 上执行脚本（FX 线程）。 */
    private static Object mainControllerRun(String script) throws Exception {
        WebView view = field(mainController, "visualEditorView");
        return view.getEngine().executeScript(script);
    }

    private static Object runScript(String script) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        runOnFx(() -> out.set(mainControllerRun(script)));
        return out.get();
    }

    /** 读工具条按钮的激活态（active 样式类）。 */
    private static boolean toolbarButtonActive(String id) throws Exception {
        AtomicReference<Boolean> on = new AtomicReference<>(false);
        runOnFx(() -> {
            FlowPane toolbar = field(mainController, "editorToolbar");
            for (Node child : toolbar.getChildren()) {
                if (child instanceof Button button && id.equals(button.getId())) {
                    on.set(button.getStyleClass().contains("active"));
                    return;
                }
            }
            throw new AssertionError("工具条上找不到按钮：" + id);
        });
        return on.get();
    }

    private static void assertEqualsInt(int expected, Object actual, String message) {
        assertTrue(actual instanceof Number && ((Number) actual).intValue() == expected,
                message + "（实际：" + actual + "）");
    }

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
