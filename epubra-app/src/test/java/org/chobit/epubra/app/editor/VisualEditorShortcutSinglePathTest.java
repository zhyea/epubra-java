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
 * <p>Robot 依赖窗口的系统级焦点，因此有两道前置检查：先跑对照组确认 Scene accelerator 会响应，
 * 再用一个无副作用的 Shift 探针确认按键<b>真的能进到 WebView 页面</b>；任一不满足即
 * {@code Assumptions} 跳过，避免门禁假红（「测不了」与「坏了」必须分开）。
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
        onFx(view::requestFocus);
        Thread.sleep(300);

        // 前置探针：控件级 requestFocus **不等于** WebKit 页面拿到 DOM 焦点。无交互会话里
        // 按键可能根本进不了页面，此时量到的「JS 桥 0 次」是「测不了」而不是「坏了」。
        // 送一个无副作用的 Shift（editor-script 只认 Tab 与 Ctrl 组合，Shift 直接放行），
        // 看 document 上的 keydown 计数器是否 +1：收不到就跳过，把前置条件不满足与功能回归
        // 分开。少了这一步，本用例会在窗口未获得前台焦点时以「expected 1 but was 0」假红
        // （2026-09-14 实测：全量跑时 skip、单跑时 fail，同一份代码）。
        onFx(() -> view.getEngine().executeScript(
                "window.__probeKeys = 0;"
                        + " document.addEventListener('keydown',"
                        + " function () { window.__probeKeys++; }, true);"
                        + " true"));
        onFx(() -> {
            javafx.scene.robot.Robot robot = new javafx.scene.robot.Robot();
            robot.keyPress(KeyCode.SHIFT);
            robot.keyRelease(KeyCode.SHIFT);
        });
        Thread.sleep(400);
        int probeKeys = ((Number) fromFx(() -> view.getEngine()
                .executeScript("Number(window.__probeKeys)"))).intValue();
        Assumptions.assumeTrue(probeKeys > 0,
                "按键未能进入 WebView 页面（DOM 焦点未就位），跳过该守卫");

        onFx(() -> view.getEngine().executeScript("window.__bridgeUndo = 0; true"));
        int accelBefore = acceleratorFires.get();
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
