package org.chobit.epubra.app.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.FlowPane;
import javafx.stage.Stage;
import org.chobit.epubra.app.activities.StatusCoordinator;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.Resource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具条上的撤销 / 重做按钮：可用态跟着历史走，点了真的生效。
 *
 * <h2>为什么需要这条守卫</h2>
 * <p>这两个按钮带 {@code toolbar-action} 类——它们没有「当前生效的格式」可言，所以
 * {@code EditorToolbarController.update()} 会跳过它们（口径正确）。但跳过整理完就是
 * <b>既没有点亮态、也没有可用态来源</b>：菜单项那边由 {@link StatusCoordinator} 管着，
 * 工具条这两枚却没人管，于是永远是一副「可点」的样子，点下去也没有任何反馈。
 *
 * <p>本类钉两件事：
 * <ol>
 *   <li><b>接线</b>——{@code MainController} 里捞出来的两枚按钮必须就是工具条上那两枚
 *       （漏接线时按钮没有任何可用态来源），且与菜单项<b>同判据</b>；</li>
 *   <li><b>真的响应</b>——{@code fire()} 之后章节内容必须回退（此前「点了没反应」）。</li>
 * </ol>
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@link VisualEditorTabUiTest} 的说明。
 */
class ToolbarHistoryButtonsUiTest {

    private static MainController mainController;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static java.nio.file.Path userDataDir;

    @BeforeAll
    static void bootFxAndLoadMainWindow() throws Exception {
        try {
            Platform.startup(FX_STARTED::countDown);
        } catch (IllegalStateException alreadyInitialized) {
            FX_STARTED.countDown();
        }
        assertTrue(FX_STARTED.await(10, TimeUnit.SECONDS), "JavaFX toolkit 启动超时");
        Platform.setImplicitExit(false);

        // 撤销会发 BookRestoredEvent → refreshAll()，可能触达预览镜像 / WebView 缓存，
        // 把用户数据根指到临时目录，避免测试写真实 ~/.Epubra/
        userDataDir = Files.createTempDirectory("epubra-history-buttons-test");
        System.setProperty("epubra.userDataDir", userDataDir.toString());

        runOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(ToolbarHistoryButtonsUiTest.class
                    .getResource("/org/chobit/epubra/app/view/main-window.fxml"));
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

    @Test
    @Timeout(60)
    @DisplayName("工具条撤销 / 重做按钮：跟着历史亮灭，且点击真的回退内容")
    void toolbarHistoryButtonsFollowHistoryAndRespond() throws Exception {
        Button undo = buttonById("undo");
        Button redo = buttonById("redo");
        assertNotNull(undo, "工具条应有 id=undo 的按钮");
        assertNotNull(redo, "工具条应有 id=redo 的按钮");

        // 接线守卫：控制器持有的必须就是工具条上这两枚真实按钮。
        // 没有这一步，「按 id 捞按钮」这段一旦写错（id 拼错、捞到别的容器）就是静默失效。
        assertSame(undo, field(mainController, "undoToolbarButton"),
                "MainController 必须把工具条上的 undo 按钮交给 StatusCoordinator");
        assertSame(redo, field(mainController, "redoToolbarButton"),
                "MainController 必须把工具条上的 redo 按钮交给 StatusCoordinator");

        runOnFx(() -> {
            BookContext ctx = field(mainController, "ctx");
            StatusCoordinator status = field(mainController, "status");
            MenuItem undoItem = field(mainController, "undoItem");
            MenuItem redoItem = field(mainController, "redoItem");

            // ---- 无可撤销内容：菜单项与工具条按钮必须一起禁用（同判据） ----
            status.updateHistoryControls();
            assertTrue(undo.isDisabled(),
                    "没有可撤销的内容时，工具条按钮必须禁用（此前永远可点、点了没反应）");
            assertTrue(redo.isDisabled(), "没有可重做的内容时，工具条按钮必须禁用");
            assertTrue(undoItem.isDisable(), "前置确认：菜单项此时也是禁用的（同一判据）");
            assertTrue(redoItem.isDisable(), "前置确认：菜单项此时也是禁用的（同一判据）");

            // 没有内容时可点也不会真的执行——禁用状态直接吞掉 fire()，正是期望行为：
            // 「没有可撤销的东西」时按钮本来就该是灰的，而不是点下去弹一句提示
            undo.fire();

            // ---- 造一次「编辑区改了一章」：先拍快照，再真的改内容 ----
            Book book = BookFactory.createEmpty("工具条撤销守卫");
            ctx.setBook(book);
            Resource chapter = book.spineResources().get(0);
            String before = chapter.asString();

            ctx.history().record(ctx.book(), ctx.dirty());
            chapter.setString(before.replace("</p>", "<p>改过的内容</p>"));
            assertFalse(ctx.book().spineResources().get(0).asString().equals(before),
                    "前置确认：书里的内容真的改了（否则下面那次撤销测的是空气）");
            status.updateHistoryControls();

            assertFalse(undo.isDisabled(), "有可撤销的内容时，工具条按钮必须被激活");
            assertFalse(undoItem.isDisable(), "菜单项与工具条按钮必须一起被激活（同判据）");
            assertTrue(redo.isDisabled(), "此刻还没有可重做的内容");

            // ---- 点击按钮：必须真的撤销（此前「没有任何响应」） ----
            undo.fire();

            assertTrue(ctx.book().spineResources().get(0).asString().equals(before),
                    "点击工具条「撤销」必须真的把章节内容回退，实际内容："
                            + ctx.book().spineResources().get(0).asString());
            status.updateHistoryControls();
            assertFalse(redo.isDisabled(), "撤销之后重做按钮必须被激活");
            assertFalse(redoItem.isDisable(), "菜单项与工具条按钮必须同步");
        });
    }

    /** 按 id 在编辑工具条里找按钮（FXML 只写了 id，注入不进控制器字段）。 */
    private static Button buttonById(String id) throws Exception {
        AtomicReference<FlowPane> toolbarRef = new AtomicReference<>();
        runOnFx(() -> toolbarRef.set(field(mainController, "editorToolbar")));
        FlowPane toolbar = toolbarRef.get();
        assertNotNull(toolbar, "editorToolbar 字段应被 FXML 注入");
        for (Node child : toolbar.getChildren()) {
            if (child instanceof Button button && id.equals(button.getId())) {
                return button;
            }
        }
        return null;
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
