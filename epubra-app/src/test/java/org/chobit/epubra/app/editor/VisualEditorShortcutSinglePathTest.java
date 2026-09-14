package org.chobit.epubra.app.editor;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：编辑器里的 <b>Ctrl+Z 只走一条路</b>（JS 桥 <em>或</em> Scene accelerator），绝不双触发。
 *
 * <h2>为什么需要这条守卫</h2>
 * <p>#68 修好按键标识后，JS 的 Ctrl+Z 分支从「永不执行」变成「真的执行」。而 FXML 菜单上
 * 也有 {@code Ctrl+Z} accelerator，两条路径都调 Java 的历史撤销。若 WebView 的
 * {@code stopPropagation()} 拦不住 Scene 的快捷键处理，一次 Ctrl+Z 就会<b>撤两步</b>——
 * 这是修复 #68 时最容易顺手引入的回归。
 *
 * <p>实测结论：焦点在编辑器内时 JS 路径命中（{@code bridgeUndo=1}）且 accelerator
 * <b>不触发</b>（{@code acceleratorFires=0}）→ 单路触发，安全。
 *
 * <p>Robot 依赖窗口的系统级焦点：无交互会话里先跑对照组确认 accelerator 会响应，
 * 不响应则 {@code Assumptions} 跳过，避免门禁假红。
 */
class VisualEditorShortcutSinglePathTest {

    private static final String DOC = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
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
    @DisplayName("编辑器内真实 Ctrl+Z：JS 桥与 Scene accelerator 只能命中一个")
    void ctrlZFiresExactlyOnePath() throws Exception {
        AtomicInteger acceleratorFires = new AtomicInteger();
        AtomicReference<WebView> viewRef = new AtomicReference<>();
        AtomicReference<Scene> sceneRef = new AtomicReference<>();
        AtomicReference<Button> outsideRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();

        onFx(() -> {
            WebView view = new WebView();
            Button outside = new Button("外部按钮");
            Scene scene = new Scene(new HBox(view, outside), 640, 480);
            scene.getAccelerators().put(
                    new KeyCodeCombination(KeyCode.Z, KeyCombination.CONTROL_DOWN),
                    acceleratorFires::incrementAndGet);
            Stage stage = new Stage();
            stage.setScene(scene);
            stage.show();
            stage.toFront();
            viewRef.set(view);
            sceneRef.set(scene);
            outsideRef.set(outside);
            stageRef.set(stage);
        });

        WebView view = viewRef.get();
        Scene scene = sceneRef.get();
        Button outside = outsideRef.get();
        Stage stage = stageRef.get();

        CountDownLatch loaded = new CountDownLatch(1);
        onFx(() -> view.getEngine().getLoadWorker().stateProperty()
                .addListener((obs, old, state) -> {
                    if (state == Worker.State.SUCCEEDED) {
                        loaded.countDown();
                    }
                }));
        onFx(() -> view.getEngine().loadContent(
                PreviewHtml.editableDocument(DOC, Theme.LIGHT), "application/xhtml+xml"));
        assertTrue(loaded.await(20, TimeUnit.SECONDS), "文档加载超时");

        // JS 桥计数替身（真机由 Java 侧 setMember 安装 VisualEditBridge）
        onFx(() -> view.getEngine().executeScript(
                "window.__bridgeUndo = 0;"
                        + " window.epubraBridge = { onUndo: function () { window.__bridgeUndo++; },"
                        + "   onRedo: function () {} };"
                        + " true"));

        onFx(stage::requestFocus);
        Thread.sleep(500);

        // 对照组：焦点在编辑器外，Ctrl+Z 必须由 accelerator 接手（顺带确认 Robot 可用）
        onFx(outside::requestFocus);
        Thread.sleep(300);
        pressCtrlOnFx(KeyCode.Z);
        Thread.sleep(400);
        Assumptions.assumeTrue(acceleratorFires.get() > 0,
                "Robot 或 accelerator 在本环境不生效，跳过该守卫");

        // 正式测量：焦点在编辑器内
        onFx(() -> view.getEngine().executeScript("window.__bridgeUndo = 0; true"));
        int accelBefore = acceleratorFires.get();
        onFx(view::requestFocus);
        Thread.sleep(300);
        pressCtrlOnFx(KeyCode.Z);
        Thread.sleep(600);

        int bridgeUndo = ((Number) fromFx(() -> view.getEngine()
                .executeScript("Number(window.__bridgeUndo)"))).intValue();
        int accelDelta = acceleratorFires.get() - accelBefore;

        assertEquals(1, bridgeUndo, "编辑器内的 Ctrl+Z 应命中 JS 桥一次");
        assertEquals(0, accelDelta,
                "JS 已处理时 Scene accelerator 不该再触发 —— 否则一次撤销会撤两步");
    }

    private static void pressCtrlOnFx(KeyCode code) throws Exception {
        onFx(() -> {
            javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
            robot.keyPress(KeyCode.CONTROL);
            robot.keyPress(code);
            robot.keyRelease(code);
            robot.keyRelease(KeyCode.CONTROL);
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
