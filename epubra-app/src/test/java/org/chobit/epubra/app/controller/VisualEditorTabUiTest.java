package org.chobit.epubra.app.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内容区三个 tab（编辑 / 源码 / 预览）的结构契约。
 *
 * <p>{@code MainController} 用<b>索引常量</b>驱动这套 tab：
 * {@code VISUAL_TAB_INDEX = 0} / {@code PREVIEW_TAB_INDEX = 2}，
 * 并排预览（{@code applyPreviewMode}）也按索引搬运节点。一旦有人调整
 * FXML 里的 tab 顺序或插入新 tab，索引就全部错位——本测试把顺序钉死。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@link StatusProgressUiTest} 的说明。
 */
class VisualEditorTabUiTest {

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
                    VisualEditorTabUiTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
            Parent root = loader.load();
            mainController = loader.getController();
            Stage stage = new Stage();
            stage.setScene(new Scene(root, 1280, 800));
            stage.show();
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("内容区依次是「编辑 / 源码 / 预览」，且各 tab 装载预期控件")
    void tabsAreInExpectedOrderAndCarryExpectedNodes() throws Exception {
        runOnFx(() -> {
            TabPane tabs = field(mainController, "editorTabs");
            assertNotNull(tabs, "editorTabs 字段应被 FXML 注入");
            assertEquals(3, tabs.getTabs().size(), "内容区应恰好有 3 个 tab");

            assertEquals("编辑", tabs.getTabs().get(0).getText(), "索引 0 必须是可视化编辑");
            assertEquals("源码", tabs.getTabs().get(1).getText(), "索引 1 必须是源码");
            assertEquals("预览", tabs.getTabs().get(2).getText(), "索引 2 必须是预览");

            WebView visual = field(mainController, "visualEditorView");
            WebView preview = field(mainController, "previewView");
            TextArea source = field(mainController, "contentArea");
            assertNotNull(visual, "visualEditorView 字段应被 FXML 注入");

            // 索引常量直接指向这些节点：顺序错位会让「编辑 tab」实际显示源码/预览
            assertTrue(isDescendant(tabs.getTabs().get(0).getContent(), visual),
                    "tab 0 的内容里应有可视化编辑器");
            assertSame(source, tabs.getTabs().get(1).getContent(), "tab 1 的内容应是源码编辑区");
            assertSame(preview, tabs.getTabs().get(2).getContent(), "tab 2 的内容应是预览视图");

            // 编辑 tab 在首位 → 打开书籍后默认落在这里，此时可视化编辑器必须是激活的那个
            assertEquals(0, tabs.getSelectionModel().getSelectedIndex(),
                    "默认应选中「编辑」tab（索引 0）");
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("格式化工具条挂在「编辑」tab 内，而不是横在 TabPane 上方")
    void toolbarLivesInsideVisualTab() throws Exception {
        runOnFx(() -> {
            TabPane tabs = field(mainController, "editorTabs");
            FlowPane toolbar = field(mainController, "editorToolbar");
            assertNotNull(toolbar, "editorToolbar 字段应被 FXML 注入");
            assertEquals(12, toolbar.getChildren().size(),
                    "工具条应有 段落/标题/引用/列表/分隔线/加粗/斜体/下划线/删除线/行内代码/链接/图片 十二个按钮");

            Node tabContent = tabs.getTabs().get(0).getContent();
            assertSame(tabContent, toolbar.getParent(),
                    "工具条必须直接挂在「编辑」tab 的内容里（源码/预览 tab 看不到它）");

            // 回归守卫：曾经它横在 TabPane 上方，三个 tab 都能看到
            assertNotSame(tabs.getParent(), toolbar.getParent(),
                    "工具条不应再与 TabPane 同级（那意味着它又回到了内容区上方）");
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("每个按钮的 id 就是格式名，且与上报表一一对应")
    void toolbarButtonsCarryFormatNamesAsIds() throws Exception {
        runOnFx(() -> {
            FlowPane toolbar = field(mainController, "editorToolbar");
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (Node child : toolbar.getChildren()) {
                assertTrue(child instanceof Button, "工具条里应只有按钮，实际：" + child);
                Button button = (Button) child;
                assertNotNull(button.getId(), "按钮缺少 id，工具条状态反射就找不到它");
                assertFalse(button.getText().isBlank(), "按钮缺少文案");
                ids.add(button.getId());
            }
            assertEquals(java.util.List.of("paragraph", "heading", "quote", "list", "rule",
                            "bold", "italic", "underline", "strike", "code", "link", "image"),
                    ids, "按钮 id（= 格式名）与顺序必须与 window.epubraQuery() 的返回值一致");
        });
    }

    private static boolean isDescendant(Node ancestor, Node node) {
        for (Node n = node; n != null; n = n.getParent()) {
            if (n == ancestor) {
                return true;
            }
        }
        return false;
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
