package org.chobit.epubra.app.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.BookFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 撤销后必须「模型 + 界面」一起回退。
 *
 * <p>这条链路曾经是断的：{@code UndoActivity.restore()} 会广播 {@code BookRestoredEvent}，
 * 但没有任何订阅者——撤销只换了 {@code Book} 实例，界面还停在旧文本上。后果不只是
 * 「看不到撤销」，而是下一次 {@code flushCurrentChapter()} 会把界面上的旧文本写回去，
 * 把撤销悄悄抹掉。
 *
 * <p>本测试用真的主窗口 + 真的编辑区跑一遍：改正文 → 撤销 → 断言界面文本与模型
 * 都回到修改前，并且 dirty 状态一并回退。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@link StatusProgressUiTest} 的说明。
 */
class UndoRefreshUiTest {

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

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(
                    UndoRefreshUiTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
        });
    }

    @Test
    @Timeout(90)
    @DisplayName("撤销后编辑区文本与模型一起回到修改前，dirty 一并回退")
    void undoRestoresBothEditorAndModel() throws Exception {
        runOnFx(() -> {
            BookContext ctx = field(mainController, "ctx");
            ctx.setBook(BookFactory.createEmpty("撤销测试"));
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());

            // 停在「源码」tab：可视化编辑器的文档是异步加载的，这里只验证撤销刷新链路
            TabPane tabs = field(mainController, "editorTabs");
            tabs.getSelectionModel().select(1);

            TextArea area = field(mainController, "contentArea");
            String original = area.getText();
            assertFalse(original.isBlank(), "新书的第一章应有初始正文");

            // 改正文：contentArea 的 textProperty 监听会记一次快照并标脏
            area.setText(original + "<p>补充一段</p>");
            assertTrue(ctx.dirty(), "改动后应为已修改状态");

            mainController.onUndo();

            assertEquals(original, area.getText(),
                    "撤销后编辑区必须显示修改前的内容（否则界面在骗人）");
            assertFalse(ctx.dirty(), "撤销回退到了快照时的状态，dirty 也应回退");

            ChapterNode current = currentNodeOf(mainController);
            assertTrue(current != null && current.resource() != null,
                    "撤销后目录里应仍能定位到同一章");
            assertEquals(original, current.resource().asString(),
                    "撤销后章节资源也要回到修改前——不能只改界面");
        });
    }

    private static ChapterNode currentNodeOf(MainController controller) throws Exception {
        Object toc = field(controller, "tocViewController");
        return (ChapterNode) toc.getClass().getMethod("currentNode").invoke(toc);
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
        assertTrue(done.await(30, TimeUnit.SECONDS), "FX 任务超时");
        if (err.get() != null) {
            throw new RuntimeException("FX task failed: " + err.get().getMessage(), err.get());
        }
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}
