package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.lib.domain.BookFactory;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
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
 * 「视图」菜单的条目契约：<b>首页（书架）只展示主题相关选项</b>。
 *
 * <p>背景：「刷新预览 / 并排预览」作用的对象是「当前打开的图书」，首页没有书——留着它们
 * 就是点了没反应的摆设。而主题（浅色 / 深色 / 护眼米黄）是全局命令，两态都得在。
 * 菜单<b>本身</b>因此两态都在，收起的是其中那组预览命令。
 *
 * <p>两条分隔线必须跟着一起收：{@code ContextMenuContent} 只跳过不可见的条目，
 * 留着分隔线会在菜单顶部留两条空档。
 *
 * <p><b>断言方式</b>：从真实 {@code MenuBar} 上读「用户实际看得见」的条目，而不是逐个
 * 查 {@code fx:id} 字段的 {@code visible}——既能挡住「漏藏一条预览命令」，也能挡住
 * 「误藏了主题项」，还能挡住「留了悬空分隔线」。
 *
 * <p><b>为什么要独立成类</b>：本类要把状态从「首页」走到「已打开图书」，必须持有自己的
 * {@code MainController} 实例（同一实例被 publish 过 {@code BookLoadedEvent} 就回不到首页态，
 * 那样两条断言只能活一条）。项目里已有多 9 个测试类各自加载 {@code main-window.fxml}，这是既有惯例。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code HomeMenuVisibilityTest} 的说明。
 */
class HomeViewMenuTest {

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
                    HomeViewMenuTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
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
    @DisplayName("首页「视图」只留主题三项（两条分隔线一起收起），打开图书后预览命令才出现")
    void viewMenuShowsOnlyThemeItemsOnHome() throws Exception {
        // 启动态即首页：没有书，「刷新预览 / 并排预览」无作用对象，必须收起
        assertEquals(List.of("浅色", "深色", "护眼米黄"), visibleViewItems(),
                "首页「视图」菜单应仅展示主题相关选项");
        assertEquals(0L, visibleViewSeparatorCount(),
                "首页不能留悬空分隔线——只藏 MenuItem 会在菜单顶部留两条空档");

        // 模拟「从书架打开一本书」：setBook + 广播 BookLoadedEvent
        runOnFx(() -> {
            BookContext ctx = contextOf();
            ctx.setBook(BookFactory.createEmpty("视图菜单契约"));
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });

        assertEquals(List.of("刷新预览", "并排预览", "浅色", "深色", "护眼米黄"), visibleViewItems(),
                "图书打开后预览命令应恢复展示，且顺序不变");
        assertEquals(2L, visibleViewSeparatorCount(),
                "有书时应恢复两条分隔线，预览组与主题组的分组关系不变");
    }

    /** 「视图」菜单里当前可见的条目文字（不含分隔线），保持 FXML 声明顺序。 */
    private static List<String> visibleViewItems() throws Exception {
        AtomicReference<List<String>> out = new AtomicReference<>();
        runOnFx(() -> out.set(viewMenu().getItems().stream()
                .filter(MenuItem::isVisible)
                .filter(item -> !(item instanceof SeparatorMenuItem))
                .map(MenuItem::getText)
                .toList()));
        return out.get();
    }

    /** 「视图」菜单里当前可见的分隔线数量。 */
    private static long visibleViewSeparatorCount() throws Exception {
        AtomicReference<Long> out = new AtomicReference<>();
        runOnFx(() -> out.set(viewMenu().getItems().stream()
                .filter(MenuItem::isVisible)
                .filter(item -> item instanceof SeparatorMenuItem)
                .count()));
        return out.get();
    }

    private static Menu viewMenu() {
        return menuBar().getMenus().stream()
                .filter(menu -> "视图".equals(menu.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("MenuBar 上找不到「视图」菜单"));
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
