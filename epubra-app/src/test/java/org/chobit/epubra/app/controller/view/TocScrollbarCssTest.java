package org.chobit.epubra.app.controller.view;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Orientation;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.chobit.epubra.app.controller.MainController;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录树滚动条 CSS 样式的 GUI 回归测试。
 *
 * <p>测试只依赖 FXML/CSS，不调用目录控制器的样式逻辑，确保视觉尺寸没有回退到
 * JavaFX 默认的滚动条宽度。
 */
class TocScrollbarCssTest {

    private static MainController mainController;
    private static Stage stage;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static final BooleanProperty stageShown = new SimpleBooleanProperty(false);

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
                    TocScrollbarCssTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
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
        Platform.runLater(() -> {
            if (stage != null) {
                stage.hide();
            }
        });
    }

    @Test
    @Timeout(60)
    void tocTreeKeepsVisibleThinScrollbar() throws Exception {
        runOnFx(() -> {
            TocController controller = fieldOf(mainController, "tocViewController");
            TreeView<ChapterNode> tree = fieldOf(controller, "tocTree");

            TreeItem<ChapterNode> root = new TreeItem<>(new ChapterNode("目录", null, null));
            root.setExpanded(true);
            for (int i = 1; i <= 80; i++) {
                root.getChildren().add(new TreeItem<>(new ChapterNode("章节 " + i, null, null)));
            }
            tree.setRoot(root);

            Scene scene = stage.getScene();
            scene.getRoot().applyCss();
            scene.getRoot().layout();

            ScrollBar vertical = tree.lookupAll(".scroll-bar").stream()
                    .map(ScrollBar.class::cast)
                    .filter(bar -> bar.getOrientation() == Orientation.VERTICAL)
                    .findFirst()
                    .orElse(null);
            javafx.scene.Node thumb = tree.lookup(".scroll-bar:vertical > .thumb");
            assertNotNull(vertical, "目录树应创建垂直滚动条");
            assertTrue(vertical.isVisible(), "目录节点超出视口时垂直滚动条应可见");
            assertNotNull(thumb, "目录树滚动条应创建可见滑块");
            assertTrue(thumb.isVisible(), "目录树滚动条滑块不应为空或隐藏");
            assertTrue(thumb.getBoundsInParent().getWidth() > 0,
                    "目录树滚动条滑块应有实际可见宽度");
            assertTrue(vertical.getWidth() >= 6 && vertical.getWidth() <= 10,
                    "目录树滚动条应使用窄布局宽度，实际宽度=" + vertical.getWidth());
            assertTrue(thumb instanceof Region, "目录树滚动条滑块应支持背景绘制");
            Region thumbRegion = (Region) thumb;
            assertTrue(thumbRegion.getBackground() != null
                            && !thumbRegion.getBackground().getFills().isEmpty(),
                    "目录树滚动条滑块应有可见背景");
            assertTrue(thumbRegion.getBackground().getFills().get(0).getInsets().getLeft() > 0,
                    "目录树滚动条滑块应通过背景内缩保持细条外观");
            assertButtonHeight(vertical, ".increment-button");
            assertButtonHeight(vertical, ".decrement-button");
        });
    }

    private static void assertButtonHeight(ScrollBar scrollBar, String selector) {
        javafx.scene.Node button = scrollBar.lookup(selector);
        assertNotNull(button, "目录树滚动条应创建端部按钮：" + selector);
        assertTrue(button.getBoundsInParent().getHeight() > 0
                        && button.getBoundsInParent().getHeight() <= 8,
                "目录树滚动条端部按钮高度应为窄尺寸，实际高度="
                        + button.getBoundsInParent().getHeight());
    }

    @SuppressWarnings("unchecked")
    private static <T> T fieldOf(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return (T) field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " on " + target.getClass());
    }

    private static void runOnFx(FxTask task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                task.runWithException();
            } catch (Throwable t) {
                error.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS), "FX 任务超时");
        if (error.get() != null) {
            throw new RuntimeException("FX task failed: " + error.get().getMessage(), error.get());
        }
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}
