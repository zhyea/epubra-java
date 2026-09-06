package com.epubra.app.controller;

import com.epubra.app.support.AppEventBus;
import com.epubra.app.support.BookContext;
import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.BookFactory;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 欢迎页收起的回归测试（2026-09-06 严重回归的固化断言）。
 *
 * <p><b>回归现象</b>：{@code hide()} 原先只把内层 {@code welcomePane} VBox 设为不可见，
 * 而 {@code fx:include} 的根 StackPane 仍 visible=true 且 pickOnBounds=true——它的透明
 * 区域会拦截整个 main-center 的鼠标命中，用户打开书后看到界面正常，但左侧目录按钮、
 * 资源按钮、编辑区全部「点了没反应」。
 *
 * <p><b>断言点</b>：BookLoadedEvent 之后，欢迎页<b>根节点</b>（include 注入到
 * MainController 的 {@code welcomePage} StackPane）必须 visible=false——visible=false
 * 的子树整体退出鼠标命中测试，这是 JavaFX 的语义保证。
 *
 * <p>真实加载 main-window.fxml（含全部子 FXML 与控制器 bind），保证覆盖
 * 「MainController.initialize 订阅 → WelcomePageController.subscribeVisibility → hide」
 * 的完整链路，而不是只测 controller 单元。
 */
class WelcomePageHideTest {

    private static MainController mainController;
    private static Stage stage;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static final BooleanProperty stageShown = new SimpleBooleanProperty(false);

    /**
     * 跨测试 class 共享 JavaFX toolkit：toolkit 全 JVM 只能 init 一次；
     * 后跑的 class 调 {@link Platform#startup} 会抛
     * {@link IllegalStateException} "Toolkit already initialized"。
     * 用 try-catch 吞掉，latch 仍正确 countDown 供后续 await 使用。
     * （早期版本用 {@code if (FX_STARTED.getCount() > 0)} 守卫，但因为每个 test class
     *  持有独立 CountDownLatch，跨 class 不生效——这是 2026-09-06 全量门禁 1 error 的根因。）
     */
    @BeforeAll
    static void bootFxAndLoadMainWindow() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    WelcomePageHideTest.class.getResource("/com/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
            stageShown.set(true);
        });
        assertTrue(stageShown.get(), "主窗口未能显示");
    }

    @AfterAll
    static void shutdownFx() {
        // 不在这里 Platform.exit()——JUnit 多 class fixture 共享 JavaFX Platform，
        // setImplicitExit(false) 下 Platform 跟 JVM 一起结束即可。
        // （早期版本在本方法末尾 Platform.exit()，后被其他使用 JavaFX 的测试
        //  cross-class 重启遇到 IllegalStateException "Platform.exit has been called"）
        Platform.runLater(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
    }

    @Test
    @Timeout(60)
    void welcomeRootHidesAfterBookLoadedAndStopsBlockingPicking() throws Exception {
        // include 根注入必须成功（双字段注入：welcomePage = 根节点）
        StackPane welcomeRoot = welcomeRootOf(mainController);
        assertNotNull(welcomeRoot, "fx:include welcomePage 的根节点未注入 MainController");
        assertTrue(welcomeRoot.isVisible(), "启动时欢迎页应可见");

        // 模拟「从欢迎页选择图书，进入编辑器」
        runOnFx(() -> {
            BookContext ctx = contextOf(mainController);
            Book book = BookFactory.createEmpty("回归测试用书");
            ctx.setBook(book);
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });

        // 关键断言：BookLoadedEvent 后根 StackPane 必须真正退出布局与命中测试。
        // 回归版本里这里仍是 visible=true——透明覆盖层拦截整个编辑区的点击。
        runOnFx(() -> {
            assertTrue(stage.isShowing());
            assertFalse(welcomeRoot.isVisible(),
                    "欢迎页根 StackPane 仍可见——它会拦截编辑区全部点击（2026-09-06 回归）");
            assertFalse(welcomeRoot.isManaged(),
                    "隐藏的欢迎页根不应继续参与布局");
        });

        // 收起后再 show()（「关闭项目」预留路径）也要能恢复
        runOnFx(() -> {
            WelcomePageController c = welcomeControllerOf(mainController);
            c.show();
            assertTrue(welcomeRoot.isVisible(), "show() 应恢复根节点可见");
            c.hide();
            assertFalse(welcomeRoot.isVisible());
        });
    }

    private static StackPane welcomeRootOf(MainController controller) throws Exception {
        return (StackPane) field(controller, "welcomePage");
    }

    private static BookContext contextOf(MainController controller) throws Exception {
        return (BookContext) field(controller, "ctx");
    }

    private static WelcomePageController welcomeControllerOf(MainController controller) throws Exception {
        return (WelcomePageController) field(controller, "welcomePageController");
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                var f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
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
