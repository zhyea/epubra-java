package org.chobit.epubra.app.controller;

import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.FlowPane;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.controller.view.ResourceController;
import org.chobit.epubra.app.controller.view.TocController;
import org.chobit.epubra.app.editor.EditorStyleControls;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

        // 本类会触发章节加载（reloadVisualEditor → 预览镜像 / WebView 缓存落盘），
        // 把用户数据根指到临时目录，避免测试写真实 ~/.Epubra/
        userDataDir = java.nio.file.Files.createTempDirectory("epubra-tab-ui-test");
        System.setProperty("epubra.userDataDir", userDataDir.toString());

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

    @AfterAll
    static void releaseUserDataDir() {
        System.clearProperty("epubra.userDataDir");
    }

    private static java.nio.file.Path userDataDir;

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
            assertEquals(20, toolbar.getChildren().size(),
                    "工具条应有 十二个格式按钮 + 图片/撤销/重做 + 放大字号/缩小字号 + 字体/文字颜色/对齐"
                            + " 共二十个控件");

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
    @DisplayName("每个按钮的 id 就是格式名；文字已换成图标，可发现性由 Tooltip 补回")
    void toolbarButtonsCarryFormatNamesAsIds() throws Exception {
        runOnFx(() -> {
            FlowPane toolbar = field(mainController, "editorToolbar");
            java.util.List<String> ids = new java.util.ArrayList<>();
            for (Node child : toolbar.getChildren()) {
                // 字体（ComboBox）/ 颜色（ColorPicker）/ 对齐（MenuButton）不是 Button，
                // 在这里跳过；它们的契约见下一个测试。
                if (!(child instanceof Button button)) {
                    continue;
                }
                assertNotNull(button.getId(), "按钮缺少 id，工具条状态反射就找不到它");
                ids.add(button.getId());
                // 文字已换成图标（ToolbarIcons.install）：断言图形存在 + 中文提示可悬停
                assertNull(button.getText(),
                        "工具条按钮应已是图标（文字清空），实际：" + button.getId());
                assertNotNull(button.getGraphic(), "按钮缺少图标图形：" + button.getId());
                assertNotNull(button.getTooltip(), "图标按钮必须有 Tooltip 兜底可发现性：" + button.getId());
                assertFalse(button.getTooltip().getText().isBlank(),
                        "Tooltip 文案不能为空：" + button.getId());
                // 图标没有文字表意，Tooltip 默认 ~1s 的显示延迟等于「没有提示」（用户实测反馈）
                assertTrue(button.getTooltip().getShowDelay().toMillis() <= 300,
                        "Tooltip 显示延迟必须压到 300ms 内，实际："
                                + button.getTooltip().getShowDelay() + "（" + button.getId() + "）");
            }
            // 顺序 = main-window.fxml 的声明顺序：样式四组（字体 / 放大 / 缩小 / 颜色 / 对齐）
            // 紧跟「段落 / 标题」之后（WPS / Office 的「字体」工具组位置），
            // 「撤销 / 重做」是动作按钮（无「当前生效格式」可言），排在最后。
            assertEquals(java.util.List.of("paragraph", "heading", "size-up", "size-down",
                            "quote", "list", "ol", "rule",
                            "bold", "italic", "underline", "strike", "code", "link",
                            "image", "undo", "redo"),
                    ids, "格式按钮的 id（= 格式名）与顺序必须与 window.epubraQuery() 的返回值一致");

            // 「图片」「撤销」「重做」「放大/缩小字号」是动作按钮（弹文件选择器 / 走快照栈 /
            // 相对跳档），不是格式状态：必须带 toolbar-action 标记，否则 updateToolbarState
            // 会把它们当格式按钮参与点亮——而 epubraQuery 永远不会返回它们的 id，
            // 它们就只是永不点亮的摆设（P3 修复的回归守卫）。
            java.util.Set<String> actions = java.util.Set.of(
                    "image", "size-up", "size-down", "undo", "redo");
            for (Node child : toolbar.getChildren()) {
                if (!(child instanceof Button button)) {
                    continue;
                }
                boolean isAction = button.getStyleClass().contains("toolbar-action");
                assertEquals(actions.contains(button.getId()), isAction,
                        "只有「图片 / 撤销 / 重做」应是动作按钮，实际：" + button.getId()
                                + " styleClass=" + button.getStyleClass());
            }
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("样式控件组：字体下拉跟随本机全部字体族，颜色用取色器，对齐三向各一条命令")
    void styleControlsCarryOptionsAndCommands() throws Exception {
        runOnFx(() -> {
            assertNotNull(field(mainController, "editorStyleControls"),
                    "样式控件组必须被构造——它是 onStyleChanged 的回显通道，漏了就到不了界面");

            // ---- 字体：按钮下拉（MenuButton），与「对齐」同形；
            //      清单 = 筛选输入框 + 默认档 + 常用档置顶 + 本机全量字体族
            MenuButton font = field(mainController, "fontButton");
            assertNotNull(font, "字体控件应被 FXML 注入");
            assertNull(font.getText(), "字体控件应是图标按钮（文字清空）——族名收在弹层里，不撑宽工具条");
            assertNotNull(font.getGraphic(), "字体控件缺少图标图形");
            assertNotNull(font.getTooltip(), "字体控件要有 Tooltip（图标控件的可发现性来源）");
            assertTrue(font.getTooltip().getShowDelay().toMillis() <= 300,
                    "Tooltip 显示延迟口径与图标按钮一致");
            assertFalse(font.isFocusTraversable(),
                    "Tab 键要留给编辑器内的列表缩进（JS 的 Tab 处理），不能被工具条控件截走");

            // 第一项是筛选输入框（CustomMenuItem），字体选项从第二项起才是 RadioMenuItem
            assertTrue(font.getItems().get(0) instanceof javafx.scene.control.CustomMenuItem,
                    "字体弹层顶部应是筛选输入框（CustomMenuItem）");
            java.util.List<RadioMenuItem> fontOptions = font.getItems().stream()
                    .filter(RadioMenuItem.class::isInstance)
                    .map(RadioMenuItem.class::cast)
                    .collect(Collectors.toList());
            assertFalse(fontOptions.isEmpty(), "字体清单不能是空的");
            assertEquals(EditorStyleControls.FONT_DEFAULT, fontOptions.get(0).getText(),
                    "「默认」档必须排在字体选项的最前（常用档置顶＋全量跟随）");
            for (RadioMenuItem option : fontOptions) {
                assertNotNull(option.getOnAction(),
                        "每个字体项都要接了命令（选中即应用），否则是点不动的摆设：" + option.getText());
            }

            // 「选择计算机上所有的字体」是本轮的核心诉求：清单必须真的跟随 Font.getFamilies()
            assertTrue(EditorStyleControls.systemFontCount() > 0,
                    "本机字体枚举不该为空，否则「全量跟随」无从谈起");
            java.util.List<String> listed = fontOptions.stream()
                    .map(MenuItem::getText).collect(Collectors.toList());
            assertTrue(listed.containsAll(EditorStyleControls.systemFonts()),
                    "字体清单应列全本机字体族，实际 " + listed.size()
                            + " 项 / 本机 " + EditorStyleControls.systemFontCount() + " 项");
            // 输入筛选：命中项保留、条数明显收窄（弹层里的输入框走的就是同一个 fontItems）
            java.util.List<String> filtered = EditorStyleControls.fontItems("yahei");
            assertTrue(filtered.contains("Microsoft YaHei"), "输入筛选应能命中常用档：" + filtered);
            assertTrue(filtered.size() < EditorStyleControls.systemFontCount(),
                    "筛选后条数应少于全量，实际 " + filtered.size());
            assertEquals(EditorStyleControls.FONT_DEFAULT, EditorStyleControls.fontItems("").get(0),
                    "空输入 = 全量列表，且仍以「默认」档打头");

            // ---- 颜色：取色器（自带标准色板与「自定义颜色…」）；null = 正文没有 color 声明
            ColorPicker color = field(mainController, "colorPicker");
            assertNotNull(color, "颜色控件应被 FXML 注入");
            assertNull(color.getValue(),
                    "初始应为「无颜色」（null）——写进正文的是空串，而不是白色声明");
            assertNotNull(color.getTooltip(), "取色器要有 Tooltip（不透明度拖到 0 = 清除）");
            assertTrue(color.getTooltip().getShowDelay().toMillis() <= 300,
                    "Tooltip 显示延迟口径与图标按钮一致");
            assertFalse(color.isFocusTraversable(), "Tab 键留给编辑器内的列表缩进");
            // 外观已换成「A + 色条」图标。ColorPicker 没有 graphic 属性，图标是**兄弟节点**
            // 叠在它上面（见 EditorStyleControls#installColorIcon），所以断言落在叠放槽里，
            // 而不是 color.getGraphic()。
            FlowPane toolbar = field(mainController, "editorToolbar");
            assertNotNull(toolbar.lookup(".toolbar-color-bar"),
                    "工具条上应有色条节点 .toolbar-color-bar——它是「当前颜色」的显示位");
            assertSame(toolbar.lookup(".toolbar-color"), color.getParent(),
                    "取色器应包在 .toolbar-color 叠放槽里（图标与它同槽才叠得上去）");

            // ---- 字号：没有下拉框了，只有「放大 / 缩小」两个档位按钮
            assertThrows(NoSuchFieldException.class, () -> field(mainController, "sizeCombo"),
                    "字号下拉已移除（#72 二轮：改成放大/缩小两个按档位跳档的按钮）");
            Button sizeUp = buttonById(toolbar, "size-up");
            Button sizeDown = buttonById(toolbar, "size-down");
            assertNotNull(sizeUp, "工具条应有「放大字号」按钮");
            assertNotNull(sizeDown, "工具条应有「缩小字号」按钮");
            assertTrue(org.chobit.epubra.app.ui.ToolbarIcons.covers("size-up")
                            && org.chobit.epubra.app.ui.ToolbarIcons.covers("size-down"),
                    "两个档位按钮都要在 ToolbarIcons 里登记图标与提示文案");

            MenuButton align = field(mainController, "alignButton");
            assertNotNull(align, "对齐控件应被 FXML 注入");
            assertEquals(java.util.List.of("左对齐", "居中", "右对齐"),
                    align.getItems().stream().map(MenuItem::getText).collect(Collectors.toList()),
                    "对齐应有且只有左 / 中 / 右三个方向");
            for (MenuItem item : align.getItems()) {
                assertNotNull(item.getGraphic(), "对齐项要带方向图标：" + item.getText());
                assertNotNull(item.getOnAction(), "对齐项必须接了命令：" + item.getText());
            }
            assertNull(align.getText(), "对齐控件应已是图标（文字清空）");
            assertNotNull(align.getGraphic(), "对齐控件缺少图标图形");
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("资源控制器的章节 provider 已接线：目录选中谁它就给谁")
    void resourceControllerChapterProviderFollowsTocSelection() throws Exception {
        runOnFx(() -> {
            TocController tocController = field(mainController, "tocViewController");
            ResourceController resourceController = field(mainController, "resourceViewController");
            Supplier<ChapterNode> provider = field(resourceController, "currentNodeProvider");

            ChapterNode node = new ChapterNode("第一章", null, null);
            tocController.setCurrentNode(node);
            try {
                assertSame(node, provider.get(),
                        "章节 provider 必须接目录树状态——漏接时插入图片恒报「请先在左侧目录中选择章节」"
                                + "（曾以从未被调用的 setter 形式存在，工具条插图全被拦）");
            } finally {
                tocController.setCurrentNode(null);
            }
        });
    }

    @Test
    @Timeout(60)
    @DisplayName("源码区的改动在切回编辑 tab 前先落进章节资源——可视化编辑器才能同步")
    void sourceEditsAreCommittedBeforeVisualReload() throws Exception {
        runOnFx(() -> {
            TocController tocController = field(mainController, "tocViewController");
            BookContext ctx = field(mainController, "ctx");
            TabPane tabs = field(mainController, "editorTabs");
            TextArea sourceArea = field(mainController, "contentArea");

            Book book = BookFactory.createEmpty("同步测试");
            ctx.setBook(book);
            org.chobit.epubra.lib.domain.Resource chapter = book.spineResources().get(0);
            tocController.setCurrentNode(new ChapterNode("第一章", chapter, null));
            try {
                // 停在源码 tab 改文本：此刻章节资源还不知道这次修改
                tabs.getSelectionModel().select(1);
                sourceArea.setText(chapter.asString().replace("</p>", "</p>")
                        + "<p>源码改动标记五十三</p>");
                assertFalse(chapter.asString().contains("源码改动标记五十三"),
                        "前置确认：源码输入不直接写章节资源（文本监听器只做撤销与标脏）");

                // 切回编辑 tab：修复点是切换时先 flushCurrentChapter 再 reload
                tabs.getSelectionModel().select(0);

                assertTrue(chapter.asString().contains("源码改动标记五十三"),
                        "切 tab 必须先把源码区文本写进章节资源，否则可视化编辑器重载的是旧内容");
            } finally {
                tocController.setCurrentNode(null);
            }
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

    /**
     * 按 id 在工具条里找按钮。
     *
     * <p>FXML 里只写了 {@code id="size-up"}（{@code Node.getId()}），没写 {@code fx:id}，
     * 所以它不会被注入成控制器字段，只能这样按 id 捞。
     */
    private static Button buttonById(FlowPane toolbar, String id) {
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
