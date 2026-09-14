package org.chobit.epubra.app.controller.view;

import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.controller.MainController;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.ChapterTemplates;
import org.chobit.epubra.lib.domain.TOCReference;
import org.chobit.epubra.lib.domain.TocEditor;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目录侧栏多级章节的契约：嵌套渲染、右键「降一级 / 升一级」、灰化规则，以及删除父章节时的子章节去向。
 *
 * <p><b>为什么要走真实 main-window</b>：层级编辑的接线散在 FXML（菜单项 onAction）、
 * {@code MainController}（一行委派）与 {@code TocController}（树操作）三处，只测
 * {@code TocController} 会漏掉「菜单点了没反应」。本类从真实 {@code TreeView} 与真实
 * {@code ContextMenu} 上读用户看得见的东西。
 *
 * <p><b>选中用「静默」方式</b>：{@code TreeView} 的选中监听会回调
 * {@code MainController.showChapter}（真加载章节内容）。本类只关心目录结构，所以选中时
 * 用 {@code ctx.setLoading(true)} 包住以跳过监听器副作用，再显式
 * {@code setCurrentNode(...)} 把「当前节点」补上——这正是 listener 里那段逻辑的等价替身。
 *
 * <p>跨 class 共享 JavaFX toolkit，见 {@code HomeMenuVisibilityTest} 的说明。
 */
@Timeout(120)
class TocLevelTest {

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
                    TocLevelTest.class.getResource("/org/chobit/epubra/app/view/main-window.fxml"));
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

    /** 每个用例换一本干净的书（createEmpty 自带「第一章」），并广播一次加载事件刷目录树。 */
    @BeforeEach
    void freshBook() throws Exception {
        runOnFx(() -> {
            BookContext ctx = context();
            ctx.setBook(BookFactory.createEmpty("多级目录契约"));
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        });
    }

    // ---- 结构：多级渲染 ----

    @Test
    @DisplayName("多级章节应渲染为嵌套树且默认展开")
    void nestedChaptersRenderAsNestedTree() throws Exception {
        withBook(book -> {
            book.addChapter("第二章", null);
            book.addChapter("第三章", null);
            TocEditor.indent(book, book.toc().roots().get(1));   // 第二章 → 第一章 的子章节
        });

        runOnFx(() -> {
            TreeItem<ChapterNode> root = tree().getRoot();
            assertEquals(2, root.getChildren().size(), "顶层应剩第一章与第三章");

            TreeItem<ChapterNode> first = root.getChildren().get(0);
            assertEquals("第一章", first.getValue().displayTitle());
            assertEquals(1, first.getChildren().size(), "第二章应挂在第一章下");
            assertEquals("第二章", first.getChildren().get(0).getValue().displayTitle());
            assertTrue(first.isExpanded(), "有子章节的节点应默认展开，否则子层级看不见");
        });
    }

    // ---- 菜单契约 ----

    @Test
    @DisplayName("右键菜单应含「降一级 / 升一级」，紧随移动组之后")
    void contextMenuOffersLevelCommands() throws Exception {
        AtomicReference<List<String>> texts = new AtomicReference<>();
        runOnFx(() -> {
            // cellFactory 的调用会挂上右键菜单，直接读它即可，不需要把 cell 挂进场景
            TreeCell<ChapterNode> cell = tree().getCellFactory().call(tree());
            assertNotNull(cell.getContextMenu(), "目录单元格应挂右键菜单");
            texts.set(cell.getContextMenu().getItems().stream()
                    .filter(item -> !(item instanceof SeparatorMenuItem))
                    .map(MenuItem::getText)
                    .toList());
        });
        assertEquals(
                List.of("添加章节", "重命名", "上移", "下移", "降一级", "升一级", "删除"),
                texts.get(),
                "层级命令应与移动命令同组，且不能插进添加/重命名与删除之间");
    }

    // ---- 行为：降级 / 升级 ----

    @Test
    @DisplayName("「降一级」应把章节挂到前一个同级章节下，并同步阅读顺序")
    void indentMovesChapterUnderPreviousSibling() throws Exception {
        withBook(book -> book.addChapter("第二章", null));
        selectByTitle("第二章");

        runOnFx(() -> toc().onIndentChapter());

        runOnFx(() -> {
            Book book = context().book();
            assertEquals(List.of("第一章"), titles(book.toc().roots()));
            assertEquals(List.of("第二章"), titles(book.toc().roots().get(0).children()));
            assertEquals(List.of("第一章", "第二章"), spineTitles(book),
                    "嵌套后阅读顺序仍由目录深度优先派生");
            assertEquals(1, tree().getRoot().getChildren().get(0).getChildren().size(),
                    "树上应体现为一级嵌套");
        });
    }

    @Test
    @DisplayName("「升一级」应把子章节移回父章节的下一个同级位置")
    void outdentReturnsChapterToTopLevel() throws Exception {
        withBook(book -> {
            book.addChapter("第二章", null);
            TocEditor.indent(book, book.toc().roots().get(1));
        });
        selectByTitle("第二章");

        runOnFx(() -> toc().onOutdentChapter());

        runOnFx(() -> {
            Book book = context().book();
            assertEquals(List.of("第一章", "第二章"), titles(book.toc().roots()));
            assertTrue(book.toc().roots().get(0).children().isEmpty(), "升级后不应残留空子列表外壳");
            assertEquals(2, tree().getRoot().getChildren().size());
            assertEquals(0, tree().getRoot().getChildren().get(0).getChildren().size(),
                    "树上应回到平级");
        });
    }

    @Test
    @DisplayName("边界：同级首项降级、顶层升级都应只给提示、不改结构")
    void levelCommandsOnBoundaryAreNoOps() throws Exception {
        selectByTitle("第一章");                     // 顶层且是同级首项

        runOnFx(() -> toc().onIndentChapter());
        runOnFx(() -> toc().onOutdentChapter());

        runOnFx(() -> {
            Book book = context().book();
            assertEquals(List.of("第一章"), titles(book.toc().roots()), "边界操作不得改动目录结构");
            assertEquals(1, spineTitles(book).size(), "阅读顺序也不应变化");
        });
    }

    @Test
    @DisplayName("灰化规则：同级首项不可降级、顶层不可升级、空单元格两项都禁用")
    void levelMenuDisableRulesFollowTreeStructure() throws Exception {
        withBook(book -> {
            book.addChapter("第二章", null);
            book.addChapter("第三章", null);
            TocEditor.indent(book, book.toc().roots().get(1));   // 第一章 > 第二章，另有顶层第三章
        });

        runOnFx(() -> {
            List<MenuItem> items = tree().getCellFactory().call(tree()).getContextMenu().getItems();
            MenuItem indent = itemNamed(items, "降一级");
            MenuItem outdent = itemNamed(items, "升一级");

            // 顶层第一章：同级首项 → 两项都不可用
            toc().updateLevelMenuState(indent, outdent, nodeOf("第一章"));
            assertTrue(indent.isDisable(), "同级首项没有前序兄弟，降级不可用");
            assertTrue(outdent.isDisable(), "顶层章节升级不可用");

            // 顶层第三章：前面有第一章 → 可降级；仍在顶层 → 不可升级
            toc().updateLevelMenuState(indent, outdent, nodeOf("第三章"));
            assertFalse(indent.isDisable(), "有前序兄弟时应可降级");
            assertTrue(outdent.isDisable(), "第三章仍在顶层，升级不可用");

            // 子节点第二章：可升级；在子列表里又是首项 → 不可降级
            toc().updateLevelMenuState(indent, outdent, nodeOf("第二章"));
            assertTrue(indent.isDisable(), "第二章是子列表里的首项，降级不可用");
            assertFalse(outdent.isDisable(), "子节点应可升级");

            // 空单元格（行尾占位）
            toc().updateLevelMenuState(indent, outdent, null);
            assertTrue(indent.isDisable() && outdent.isDisable(), "空单元格两项都应禁用");
        });
    }

    // ---- 删除语义：子章节提升为同级 ----

    @Test
    @DisplayName("删除父章节时子章节提升为同级，阅读顺序不留分叉")
    void deletingParentPromotesChildren() throws Exception {
        withBook(book -> {
            book.addChapter("第二章", null);
            TocEditor.indent(book, book.toc().roots().get(1));   // 第一章 > 第二章
        });
        selectByTitle("第一章");

        runOnFx(() -> toc().onDeleteChapter());

        runOnFx(() -> {
            Book book = context().book();
            assertEquals(List.of("第二章"), titles(book.toc().roots()),
                    "子章节应提升为顶层章节，而不是跟着父章节一起脱离目录");
            assertEquals(1, book.spineResources().size(), "被删章节的资源应一并移出阅读顺序");
            assertEquals(List.of("第二章"), spineTitles(book), "目录与阅读顺序不得分叉");
            assertEquals(1, tree().getRoot().getChildren().size());
        });
    }

    // ---- 工具 ----

    /** 在 FX 线程里改书 + 刷树；改完目录结构必须刷新，否则断言读到的还是旧树。 */
    private static void withBook(java.util.function.Consumer<Book> mutator) throws Exception {
        runOnFx(() -> {
            mutator.accept(context().book());
            toc().refresh();
        });
    }

    /**
     * 按标题选中目录节点——<b>必须走真实选中路径</b>。
     *
     * <p>曾经用 {@code ctx.setLoading(true)} 包住选中以「跳过加载章节的副作用」，再手动
     * {@code setCurrentNode}。那是错的：{@code MainController.showChapter} 会先
     * {@code flushCurrentChapter()} 把源码区内容按 {@code currentNode} 回写，而静默选中
     * 制造了「currentNode 已指向新章节、编辑区还装着旧章节」的不一致，下一次刷新就把旧章节
     * 的正文永久写进了新章节的 XHTML（实测：第二章的标题变成「第一章」）。
     */
    private static void selectByTitle(String title) throws Exception {
        runOnFx(() -> {
            TreeItem<ChapterNode> item = findByTitle(tree().getRoot(), title);
            assertNotNull(item, "目录树上找不到章节：" + title);
            tree().getSelectionModel().select(item);
        });
    }

    private static ChapterNode nodeOf(String title) throws Exception {
        TreeItem<ChapterNode> item = findByTitle(tree().getRoot(), title);
        assertNotNull(item, "目录树上找不到章节：" + title);
        return item.getValue();
    }

    private static TreeItem<ChapterNode> findByTitle(TreeItem<ChapterNode> parent, String title) {
        for (TreeItem<ChapterNode> child : parent.getChildren()) {
            ChapterNode node = child.getValue();
            if (node != null && title.equals(node.displayTitle())) {
                return child;
            }
            TreeItem<ChapterNode> nested = findByTitle(child, title);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static MenuItem itemNamed(List<MenuItem> items, String text) {
        return items.stream()
                .filter(item -> text.equals(item.getText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("右键菜单里找不到：" + text));
    }

    private static List<String> titles(List<TOCReference> nodes) {
        return nodes.stream().map(TOCReference::title).toList();
    }

    private static List<String> spineTitles(Book book) {
        return book.spineResources().stream()
                .map(resource -> ChapterTemplates.extractTitle(resource.asString()))
                .toList();
    }

    private static BookContext context() throws Exception {
        return (BookContext) field(mainController, "ctx");
    }

    @SuppressWarnings("unchecked")
    private static TocController toc() throws Exception {
        return (TocController) field(mainController, "tocViewController");
    }

    @SuppressWarnings("unchecked")
    private static TreeView<ChapterNode> tree() throws Exception {
        return (TreeView<ChapterNode>) field(toc(), "tocTree");
    }

    private static Object field(Object target, String name) throws Exception {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                var declared = type.getDeclaredField(name);
                declared.setAccessible(true);
                return declared.get(target);
            } catch (NoSuchFieldException notHere) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " (在 " + target.getClass().getName() + " 及其父类中)");
    }

    private static void runOnFx(FxTask task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> err = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                task.runWithException();
            } catch (Throwable t) {
                err.set(t);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS), "FX 任务超时");
        if (err.get() != null) {
            throw new RuntimeException("FX task failed: " + err.get().getMessage(), err.get());
        }
    }

    @FunctionalInterface
    private interface FxTask {
        void runWithException() throws Exception;
    }
}
