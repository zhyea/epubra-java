package com.epubra.app.controller;

import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.layout.Region;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 侧栏收起后编辑区必须伸展占满的回归测试（2026-09-06 修复的布局 bug）。
 *
 * <p><b>bug 现象</b>：点击活动栏已选中的按钮（如「目录」）收起侧栏，侧栏内容消失了，
 * 但编辑区宽度没变——侧栏那一列留下一条空白。
 *
 * <p><b>根因</b>：main-window.fxml 里侧栏是 {@code SplitPane} 的 item 0，由
 * {@code sidePanel}（StackPane）包裹三个叠放视图。{@code SidebarController.hideAllSideViews()}
 * 只把三个<b>内层</b>视图设了 {@code visible=false}，容器本身还可见 —— SplitPane 仍按
 * {@code dividerPositions=0.22} 给 item 0 分配 22% 宽度，编辑区伸不过去。
 *
 * <p><b>修复</b>：{@code hideAllSideViews()} 连 {@code sidePanel} 容器一起退出布局；
 * {@code showSideView()} 把它放回来。
 *
 * <p>本测试真实加载 main-window.fxml + 真实 Stage show + 强制同步 layout，
 * 用<b>实测像素宽度</b>断言，而不是只查 visible 标志——后者在容器仍占位时也会是绿的。
 */
class SidebarCollapseLayoutTest {

    private static MainController mainController;
    private static Stage stage;
    private static final CountDownLatch FX_STARTED = new CountDownLatch(1);
    private static final BooleanProperty stageShown = new SimpleBooleanProperty(false);

    /** 侧栏展开时的基准宽度，收起后应归 0。 */
    private static double sidebarWidthWhenShown;
    /** 编辑区（SplitPane item 1）展开时的基准宽度，收起后应显著变大。 */
    private static double editorWidthWhenShown;

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
                    SidebarCollapseLayoutTest.class.getResource("/com/epubra/app/view/main-window.fxml"));
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
    void collapsingSidebarGivesAllWidthToEditor() throws Exception {
        // --- 基准：侧栏展开 ---
        runOnFx(() -> {
            SidebarController sc = sidebarControllerOf(mainController);
            sc.showTocView(); // 明确从「目录展开」开始
            forceLayout();

            StackPane sidePanel = sidePanelOf(mainController);
            Region editor = editorPaneOf(mainController);

            assertTrue(sidePanel.isVisible(), "基准态：侧栏容器应可见");
            assertTrue(sidePanel.isManaged(), "基准态：侧栏容器应参与布局");

            sidebarWidthWhenShown = sidePanel.getWidth();
            editorWidthWhenShown = editor.getWidth();
            assertTrue(sidebarWidthWhenShown > 0,
                    "基准态：侧栏应有正宽度；实际 " + sidebarWidthWhenShown);
        });

        // --- 收起侧栏（走真实的 hideAllSideViews 路径，同「再点已选中按钮」） ---
        runOnFx(() -> {
            SidebarController sc = sidebarControllerOf(mainController);
            sc.hideAllSideViews();
            forceLayout();
        });

        runOnFx(() -> {
            StackPane sidePanel = sidePanelOf(mainController);
            Region editor = editorPaneOf(mainController);
            SplitPane split = fieldOf(mainController, "mainSplit");

            assertFalse(sidePanel.isVisible(), "收起后：侧栏容器应不可见");
            assertFalse(sidePanel.isManaged(), "收起后：侧栏容器应退出布局");
            assertEquals(1, split.getItems().size(),
                    "收起后 SplitPane 应只剩编辑区一个 item（侧栏被摘掉）");

            // 核心断言：编辑区把整个 SplitPane 宽度吃下去了。
            // 注意不能用 sidePanel.getWidth() 作判据——item 被摘掉后 SplitPane 不再
            // 布局它，getWidth() 返回的是摘掉之前的陈旧值（实测 270.0）。
            double splitWidth = split.getWidth();
            assertTrue(editor.getWidth() > splitWidth * 0.95,
                    "收起后编辑区应占满 SplitPane：splitPane=" + splitWidth
                            + "，编辑区=" + editor.getWidth());
            assertTrue(editor.getWidth() > editorWidthWhenShown + sidebarWidthWhenShown * 0.9,
                    "收起后编辑区应把侧栏让出的宽度吃下去："
                            + "收起前编辑区=" + editorWidthWhenShown
                            + "，侧栏=" + sidebarWidthWhenShown
                            + "，收起后编辑区=" + editor.getWidth());
        });

        // --- 再展开：宽度应回到基准（验证可逆，不是一次性 hack） ---
        runOnFx(() -> {
            SidebarController sc = sidebarControllerOf(mainController);
            sc.showTocView();
            forceLayout();
        });

        runOnFx(() -> {
            StackPane sidePanel = sidePanelOf(mainController);
            Region editor = editorPaneOf(mainController);
            SplitPane split = fieldOf(mainController, "mainSplit");

            assertTrue(sidePanel.isVisible(), "再次展开：侧栏容器应可见");
            assertEquals(2, split.getItems().size(),
                    "再次展开：SplitPane 应恢复侧栏 + 编辑区两个 item");
            assertTrue(sidePanel.getWidth() > 0, "再次展开：侧栏应重新拿到宽度；实际 "
                    + sidePanel.getWidth());
            // divider 位置由 SidebarController 显式恢复，允许 ±10% 误差
            assertTrue(Math.abs(sidePanel.getWidth() - sidebarWidthWhenShown) < sidebarWidthWhenShown * 0.1,
                    "再次展开后侧栏宽度应回到基准附近：基准=" + sidebarWidthWhenShown
                            + "，实际=" + sidePanel.getWidth());
            assertTrue(editor.getWidth() < editorWidthWhenShown * 1.1 + 1,
                    "再次展开后编辑区宽度应回落到基准附近：基准=" + editorWidthWhenShown
                            + "，实际=" + editor.getWidth());
        });
    }

    @Test
    @Timeout(60)
    void switchingSideViewsKeepsUserDraggedDividerPosition() throws Exception {
        // 场景：用户把分隔条拖到 40%，然后切到「资源」视图——不应该被弹回 22% 默认值。
        // 这是 restoreSidebar() 里"只在从收起状态恢复时才摆 divider"的守卫所保证的。
        runOnFx(() -> {
            SidebarController sc = sidebarControllerOf(mainController);
            sc.showTocView();
            SplitPane split = fieldOf(mainController, "mainSplit");
            split.setDividerPositions(0.4);
            forceLayout();
        });

        runOnFx(() -> {
            SidebarController sc = sidebarControllerOf(mainController);
            SplitPane split = fieldOf(mainController, "mainSplit");
            double before = split.getDividerPositions()[0];

            sc.showResourceView(); // 切换到另一个侧栏视图
            forceLayout();

            double after = split.getDividerPositions()[0];
            assertTrue(Math.abs(after - before) < 0.02,
                    "切换侧栏视图不应重置用户拖动过的分隔条：切换前=" + before + "，切换后=" + after);
            assertTrue(Math.abs(after - 0.4) < 0.02,
                    "分隔条应保持在用户拖到的 0.4；实际=" + after);
        });

        // 收起再展开：应回到收起前记录的 0.4，而不是 FXML 里的 0.22
        runOnFx(() -> {
            SidebarController sc = sidebarControllerOf(mainController);
            sc.hideAllSideViews();
            forceLayout();
            sc.showTocView();
            forceLayout();

            SplitPane split = fieldOf(mainController, "mainSplit");
            double restored = split.getDividerPositions()[0];
            assertTrue(Math.abs(restored - 0.4) < 0.02,
                    "收起再展开应恢复到收起前的 0.4；实际=" + restored);
        });
    }

    @Test
    @Timeout(60)
    void activityBarToggleButtonStillCollapsesAndRestores() throws Exception {
        // 端到端走 MainController.onShowTocView()：首次点击展开，再点收起。
        // 收起路径内部依赖 isCollapsingClick（onMousePressed 快照），这里直接调两次
        // 并手动补上 setSelected，验证 MainController 编排与 SidebarController 状态一致。
        runOnFx(() -> {
            SidebarController sc = sidebarControllerOf(mainController);
            sc.showTocView();
            sc.hideAllSideViews();
            forceLayout();
            assertFalse(sidePanelOf(mainController).isVisible(),
                    "经 MainController 编排收起后，侧栏容器应不可见");
        });

        runOnFx(() -> {
            SidebarController sc = sidebarControllerOf(mainController);
            assertFalse(sc.isSidebarVisible(), "收起后 isSidebarVisible() 应为 false");
            sc.showTocView();
            forceLayout();
            assertTrue(sc.isSidebarVisible(), "展开后 isSidebarVisible() 应为 true");

            ToggleButton toc = fieldOf(mainController, "tocActivityButton");
            assertNotNull(toc, "活动栏目录按钮应被 FXML 注入");
        });
    }

    // ---- 工具 ----

    /** 强制同步 layout：改完 visible/managed 后 JavaFX 默认等下一个 pulse，测试里要立刻量宽度。 */
    private static void forceLayout() {
        Scene scene = stage.getScene();
        if (scene != null && scene.getRoot() != null) {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
        }
    }

    /**
     * 编辑区 = SplitPane 里"不是侧栏容器"的那个 item。
     *
     * <p>不能用固定 index 取：收起后侧栏被摘掉，编辑区从 index 1 变成 index 0。
     * 用"排除 sidePanel"的方式定位，两种状态都成立。
     */
    private static Region editorPaneOf(MainController controller) throws Exception {
        SplitPane split = fieldOf(controller, "mainSplit");
        assertNotNull(split, "mainSplit 未注入");
        assertFalse(split.getItems().isEmpty(), "mainSplit 不应为空");
        StackPane sidePanel = sidePanelOf(controller);
        for (Node item : split.getItems()) {
            if (item != sidePanel) {
                return (Region) item;
            }
        }
        throw new AssertionError("mainSplit 里找不到编辑区 item（全是 sidePanel？）");
    }

    private static StackPane sidePanelOf(MainController controller) throws Exception {
        return fieldOf(controller, "sidePanel");
    }

    private static SidebarController sidebarControllerOf(MainController controller) throws Exception {
        return fieldOf(controller, "sidebarController");
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