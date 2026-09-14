package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 顶部菜单在「首页（书架）」与「已打开图书」两态下的显隐契约。
 *
 * <p><b>需求</b>：首页是书架，没有打开的图书，「编辑 · 章节 · 插入 · 工具」四个菜单既无操作
 * 对象也无意义，不应展示；「文件 · 视图 · 帮助」是全局命令（新建 / 打开 / 工作空间 / 主题切换 /
 * 关于），两态都保留。切回书架同样收起（{@code WorkspaceActivity.onLeftWorkspace}）。
 *
 * <p><b>断言方式</b>：从真实 {@code MenuBar} 上读「用户实际看得见」的菜单标题列表，而不是逐字段
 * 查 {@code visible}——这样既能挡住「漏藏一个菜单」，也能挡住「误藏了文件/视图/帮助」。
 *
 * <p><b>为什么要独立成类</b>：显隐由 {@code MainController} 的私有开关统一切换，测试要让状态
 * 从「首页」走到「已打开图书」，必须持有自己的 {@code MainController} 实例（同一个实例被别的
 * 测试 publish 过 BookLoadedEvent 之后就再也回不到首页态了）。项目里另有多 8 个测试类同样各自
 * 加载 main-window.fxml，这是既有惯例。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code WelcomePageHideTest} 的说明。
 */
class HomeMenuVisibilityTest {

    private static MainController mainController;
    private static Stage stage;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);

    @BeforeAll
    static void bootFxAndLoadMainWindow() throws Exception {
        // toolkit 全 JVM 只 init 一次；后跑的 class 调 Platform.startup 会 IllegalStateException，
        // 吞掉即可（latch 仍要 countDown 供后续 await）。
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    HomeMenuVisibilityTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
        });
    }

    @AfterAll
    static void hideStage() {
        // 不 Platform.exit()：多 class 共享 Platform，setImplicitExit(false) 下随 JVM 结束即可。
        Platform.runLater(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("首页只留 文件/视图/帮助，打开图书后 编辑/章节/插入/工具 才出现")
    void editorMenusOnlyVisibleWithAnOpenBook() throws Exception {
        // 启动态即首页：编辑类菜单没有操作对象，必须收起；全局菜单一个都不能少
        assertEquals(List.of("文件", "视图", "帮助"), visibleMenuTexts(),
                "首页只应展示「文件 / 视图 / 帮助」，编辑类菜单不该出现");

        // 模拟「从书架打开一本书」：setBook + 广播 BookLoadedEvent
        runOnFx(() -> {
            BookContext ctx = contextOf();
            ctx.setBook(BookFactory.createEmpty("菜单显隐契约"));
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });

        assertEquals(List.of("文件", "编辑", "章节", "插入", "视图", "工具", "帮助"), visibleMenuTexts(),
                "图书打开后「编辑 / 章节 / 插入 / 工具」应恢复展示，且顺序不变");
    }

    /** 读取 MenuBar 上当前可见的菜单标题（保持 FXML 声明顺序）。 */
    private static List<String> visibleMenuTexts() throws Exception {
        AtomicReference<List<String>> out = new AtomicReference<>();
        runOnFx(() -> out.set(menuBar().getMenus().stream()
                .filter(Menu::isVisible)
                .map(Menu::getText)
                .toList()));
        return out.get();
    }

    private static MenuBar menuBar() {
        assertNotNull(stage.getScene(), "主窗口未挂 Scene");
        Parent root = stage.getScene().getRoot();
        assertTrue(root instanceof BorderPane, "main-window.fxml 根应为 BorderPane，实际：" + root);
        Object top = ((BorderPane) root).getTop();
        assertNotNull(top, "MenuBar 应挂在 BorderPane 的 top 上");
        return (MenuBar) top;
    }

    private static BookContext contextOf() throws Exception {
        var field = MainController.class.getDeclaredField("ctx");
        field.setAccessible(true);
        return (BookContext) field.get(mainController);
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
