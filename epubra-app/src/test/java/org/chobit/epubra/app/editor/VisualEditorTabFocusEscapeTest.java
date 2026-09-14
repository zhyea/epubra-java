package org.chobit.epubra.app.editor;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 端到端守卫：真实按键下 <b>Tab 永远不把焦点带出编辑器</b>（#68）。
 *
 * <h2>产品决策</h2>
 * <p>编辑器内一律吞掉 Tab：列表里缩进 / 降级，不在列表里有意不作为但照样拦掉默认行为。
 * 否则 JavaFX 的焦点遍历会把焦点送出编辑器（用户实测「按 Tab 操作超出了编辑器」）。
 * 键盘用户要跳出编辑器走 Ctrl+Tab（带修饰键不进这一段）。
 *
 * <h2>为什么必须用 Robot，不能用合成事件</h2>
 * <ul>
 *   <li>{@code Event.fireEvent(scene, new KeyEvent(...))} —— <b>既进不了 WebKit，也不触发
 *       Scene 的焦点遍历</b>；对照组（焦点放在 WebView 外的 Button 上按 Tab）焦点原地不动，
 *       说明这条路根本复现不了真实按键。</li>
 *   <li>程序化构造的 {@code KeyboardEvent} 带着手工塞的 {@code key: 'Tab'}，能驱动 DOM 逻辑，
 *       但<b>照不出 JavaFX 的真实映射</b>（真机 {@code e.key} 是空串）。</li>
 * </ul>
 * <p>只有 {@code javafx.scene.robot.Robot} 能同时覆盖「映射」与「焦点遍历」两件事。
 *
 * <h2>为什么带 assumeTrue</h2>
 * <p>Robot 依赖窗口拿到系统级焦点。无交互会话里送不出按键——此时 {@code Assumptions} 直接跳过，
 * <b>不让门禁产生假红</b>；有桌面时它是真守卫。无 Robot 依赖的映射契约由
 * {@link VisualEditorKeyMappingTest} 常态覆盖。
 */
class VisualEditorTabFocusEscapeTest {

    private static final String DOC_IN_LIST = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
            + "<head><title>第一章</title></head>"
            + "<body><ul><li>甲</li><li>乙</li></ul></body></html>";

    private static final String DOC_PLAIN_PARAGRAPH = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
            + "<html xmlns=\"http://www.w3.org/1999/xhtml\">"
            + "<head><title>第一章</title></head>"
            + "<body><p>正文</p></body></html>";

    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    @BeforeAll
    static void bootFx() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException already) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX 启动超时");
        Platform.setImplicitExit(false);
    }

    @Test
    @Timeout(90)
    @DisplayName("光标在列表里按真实 Tab：缩进生效，且焦点不逃出编辑器")
    void realTabInListIndentsAndKeepsFocusInside() throws Exception {
        Harness harness = newHarness(DOC_IN_LIST);
        // 光标放进第二个列表项（乙）——「在列表里按 Tab」的真实场景
        harness.runScript(
                "(function () {"
                        + " var lis = document.body.querySelectorAll('li');"
                        + " var li = lis[lis.length - 1];"
                        + " var r = document.createRange(); r.selectNodeContents(li); r.collapse(true);"
                        + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                        + " return true; })()");
        harness.assumeRobotWorks();

        harness.pressTab();
        pressTabOnFx(false);

        assertEquals(Boolean.TRUE, harness.eval("!!document.querySelector('ul > li > ul > li')"),
                "真实 Tab 必须把列表项缩进成子列表");
        harness.assertFocusStayedInside("列表内 Tab");
    }

    @Test
    @Timeout(90)
    @DisplayName("光标不在列表里按真实 Tab：有意不作为，但焦点仍留在编辑器内")
    void realTabOutsideListKeepsFocusInside() throws Exception {
        Harness harness = newHarness(DOC_PLAIN_PARAGRAPH);
        // 光标放进普通段落——此时没有可缩进的列表项
        harness.runScript(
                "(function () {"
                        + " var p = document.body.querySelector('p');"
                        + " var r = document.createRange(); r.selectNodeContents(p); r.collapse(true);"
                        + " var s = window.getSelection(); s.removeAllRanges(); s.addRange(r);"
                        + " return true; })()");
        harness.assumeRobotWorks();

        String before = harness.text();
        harness.pressTab();
        pressTabOnFx(false);

        assertEquals(before, harness.text(), "有意不作为：正文不该被改动（尤其不能插入制表符）");
        harness.assertFocusStayedInside("非列表 Tab");
    }

    // ------------------------------------------------------------------ 脚手架

    /** 一个「WebView + 窗口外可聚焦按钮」的场景，用来观察焦点会不会逃出编辑器。 */
    private static final class Harness {
        private final WebView view;
        private final Scene scene;
        private final Button outside;

        Harness(WebView view, Scene scene, Button outside) {
            this.view = view;
            this.scene = scene;
            this.outside = outside;
        }

        String text() throws Exception {
            Object out = eval("document.body.textContent");
            return String.valueOf(out);
        }

        Object eval(String script) throws Exception {
            return fromFx(() -> view.getEngine().executeScript(script));
        }

        void runScript(String script) throws Exception {
            onFx(() -> view.getEngine().executeScript(script));
        }

        /** 先跑对照组：焦点在窗口外按钮上按 Tab 必须能把焦点移进 WebView，否则跳过。 */
        void assumeRobotWorks() throws Exception {
            onFx(outside::requestFocus);
            Thread.sleep(300);
            pressTabOnFx(false);
            Thread.sleep(400);
            Assumptions.assumeTrue(
                    fromFx(scene::getFocusOwner) != outside,
                    "Robot 在本环境送不出按键（窗口无系统焦点），跳过端到端守卫");
        }

        /** 焦点交回编辑器，再按一次真实 Tab。 */
        void pressTab() throws Exception {
            onFx(view::requestFocus);
            Thread.sleep(300);
        }

        void assertFocusStayedInside(String what) throws Exception {
            Object owner = fromFx(scene::getFocusOwner);
            assertEquals(view, owner,
                    what + " 把焦点带出了编辑器（焦点落到了 " + owner + "）—— "
                            + "preventDefault 没能拦住 JavaFX 的焦点遍历");
        }
    }

    private static Harness newHarness(String doc) throws Exception {
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<Scene> sceneRef = new AtomicReference<>();
        AtomicReference<Button> outsideRef = new AtomicReference<>();

        onFx(() -> {
            WebView view = new WebView();
            Button outside = new Button("外部按钮");
            Scene scene = new Scene(new HBox(view, outside), 640, 480);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            stage.toFront();
            stage.requestFocus();
            viewRef.set(view);
            sceneRef.set(scene);
            outsideRef.set(outside);
        });

        WebView view = viewRef.get();
        CountDownLatch loaded = new CountDownLatch(1);
        onFx(() -> view.getEngine().getLoadWorker().stateProperty()
                .addListener((obs, old, state) -> {
                    if (state == Worker.State.SUCCEEDED) {
                        loaded.countDown();
                    }
                }));
        onFx(() -> view.getEngine().loadContent(
                PreviewHtml.editableDocument(doc, Theme.LIGHT), "application/xhtml+xml"));
        assertTrue(loaded.await(20, TimeUnit.SECONDS), "文档加载超时");
        Thread.sleep(500);

        return new Harness(view, sceneRef.get(), outsideRef.get());
    }

    private static void pressTabOnFx(boolean shift) throws Exception {
        onFx(() -> {
            javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
            if (shift) {
                robot.keyPress(KeyCode.SHIFT);
            }
            robot.keyPress(KeyCode.TAB);
            robot.keyRelease(KeyCode.TAB);
            if (shift) {
                robot.keyRelease(KeyCode.SHIFT);
            }
        });
    }

    private static void onFx(FxAction action) throws Exception {
        AtomicReference<Throwable> err = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX 任务超时");
        if (err.get() != null) {
            throw new RuntimeException("FX task failed: " + err.get(), err.get());
        }
    }

    private static Object fromFx(FxCall call) throws Exception {
        AtomicReference<Object> out = new AtomicReference<>();
        onFx(() -> out.set(call.get()));
        return out.get();
    }

    @FunctionalInterface
    private interface FxAction {
        void run() throws Exception;
    }

    @FunctionalInterface
    private interface FxCall {
        Object get() throws Exception;
    }
}
