package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.support.context.AppEventBus;
import org.chobit.epubra.app.support.platform.AsyncTasks;
import org.chobit.epubra.app.support.context.BookContext;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 状态栏进度条（B1 异步化）的端到端契约：FXML 加载 → 字段注入 → 进度条初态隐藏 →
 * ProgressController.begin/done 触发 visible/managed 切换。
 *
 * <p>不直接覆盖 6 个长操作（{@code DocumentController.openFileAsync} 等）的回归——
 * 那些路径由 {@link CoverCardGuiTest} 与本类共同保证：只要 ProgressBar 接入正确，
 * 任何 AsyncTasks.runIo 都自动获得「开始 → 显示、结束 → 隐藏」的行为。
 *
 * <p>跨 class 共享 JavaFX toolkit：与 {@link CoverCardGuiTest} / {@link WelcomePageHideTest}
 * 同 JVM 复用同一个 Platform.startup()，靠 try-catch 吞 IllegalStateException。
 */
class StatusProgressUiTest {

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
                    StatusProgressUiTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
            stageShown.set(true);
        });
        assertTrue(stageShown.get(), "主窗口未能显示");
    }

    @Test
    @Timeout(60)
    void progressBarAndLabelAreInjectedFromFxml() throws Exception {
        runOnFx(() -> {
            ProgressBar bar = fieldOf(mainController, "statusProgressBar");
            Label label = fieldOf(mainController, "statusProgressLabel");
            Region divider = fieldOf(mainController, "statusProgressDivider");

            assertNotNull(bar, "ProgressBar 字段应被 FXML 注入");
            assertNotNull(label, "进度标签字段应被 FXML 注入");
            assertNotNull(divider, "进度分隔竖线应被 FXML 注入");

            // 初态：visible=false + managed=false，长操作没跑时应隐藏
            assertFalse(bar.isVisible(), "ProgressBar 启动时不应可见");
            assertFalse(bar.isManaged(), "ProgressBar 启动时不应参与布局");
            assertFalse(label.isVisible(), "进度标签启动时不应可见");
            assertFalse(label.isManaged(), "进度标签启动时不应参与布局");
            assertFalse(divider.isVisible(), "进度分隔竖线启动时不应可见");
        });
    }

    @Test
    @Timeout(60)
    void progressSinkBeginShowsUiAndDoneHidesUi() throws Exception {
        // 触发 book-loaded 让 status 全部刷一遍；不影响进度条初态（与进度条是独立通道）
        runOnFx(() -> {
            BookContext ctx = (BookContext) field(mainController, "ctx");
            Book book = BookFactory.createEmpty("进度测试");
            ctx.setBook(book);
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });

        runOnFx(() -> {
            // 通过反射调私有的 progressSink()——锁定 progressBar/label/divider 的
            // 显示/隐藏契约，不依赖具体业务方法。
            Method m = MainController.class.getDeclaredMethod("progressSink");
            m.setAccessible(true);
            AsyncTasks.ProgressController sink = (AsyncTasks.ProgressController) m.invoke(mainController);

            sink.begin("测试开始");
            ProgressBar bar = fieldOf(mainController, "statusProgressBar");
            Label label = fieldOf(mainController, "statusProgressLabel");
            Region divider = fieldOf(mainController, "statusProgressDivider");

            assertTrue(bar.isVisible(), "begin 后 ProgressBar 应可见");
            assertTrue(bar.isManaged(), "begin 后 ProgressBar 应参与布局");
            assertTrue(label.isVisible(), "begin 后标签应可见");
            assertEquals("测试开始", label.getText(), "标签文本应等于传入的 title");
            assertTrue(divider.isVisible(), "begin 后分隔竖线应可见");
            assertEquals(-1, bar.getProgress(), 0.001,
                    "begin 后进度条应进入 indeterminate 模式（progress = -1）");

            sink.update(0.5);
            assertEquals(0.5, bar.getProgress(), 0.001,
                    "update 应把进度条推送到指定 fraction");

            sink.update(2.0); // 越界：ProgressController 内 clamp01 应拦到 1
            assertEquals(1.0, bar.getProgress(), 0.001, "越界 fraction 应被 clamp 到 1");

            sink.done();
            assertFalse(bar.isVisible(), "done 后 ProgressBar 应隐藏");
            assertFalse(bar.isManaged(), "done 后 ProgressBar 应退出布局");
            assertFalse(label.isVisible(), "done 后标签应隐藏");
            assertEquals("", label.getText(), "done 后标签文本应清空");
            assertFalse(divider.isVisible(), "done 后分隔竖线应隐藏");
        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T fieldOf(Object target, String name) throws Exception {
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

    private static Object field(Object target, String name) throws Exception {
        Class<?> c = target.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField(name);
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