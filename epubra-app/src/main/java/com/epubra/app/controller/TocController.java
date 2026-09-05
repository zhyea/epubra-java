package com.epubra.app.controller;

import com.epubra.app.support.BookContext;
import com.epubra.app.support.TextSearch;
import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.domain.TOCReference;
import com.epubra.epublib.domain.TocEditor;
import com.epubra.epublib.util.Hrefs;
import javafx.fxml.FXML;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.TransferMode;

import java.util.Collections;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 目录树控制器：章节树渲染、选中同步、拖拽排序与重命名。
 *
 * <p>作为 {@code toc-view.fxml} 的 {@code fx:controller} 由 FXML 实例化：
 * 目录树节点经 {@code @FXML} 注入，{@link BookContext} 与回调在父控制器
 * {@code initialize()} 阶段通过 {@link #bind} 注入——FXML 加载时子控制器先于父构造，
 * 此时还不能触碰 ctx。因此本类不得定义 {@code initialize()} 方法。
 *
 * <p>对外接口：bind 后调 {@link #setOnChapterSelected(Consumer)}（通常是
 * {@code MainController::showChapter}）。本类不直接刷正文 / 预览，只负责目录树。
 */
public class TocController {

    @FunctionalInterface
    public interface StatusSink {
        void setStatus(String message);
    }

    @FunctionalInterface
    public interface WarningSink {
        void warn(String message);
    }

    @FXML
    private TreeView<ChapterNode> tocTree;

    private BookContext ctx;
    private StatusSink status;
    private WarningSink warner;

    /** 拖拽相关：onAction 操作（add/delete/move 等）走这里，UndoController 已在 MainController 持有。 */
    @FunctionalInterface
    public interface BookAction {
        void run();
    }

    /** 章节切换通知。{@link MainController#showChapter(ChapterNode)} 作为参数传入。 */
    private Consumer<ChapterNode> onChapterSelected = node -> {};

    /** 标记「当前变更需要进入撤销栈」。 */
    private BookAction beginChange;

    /** 正在被拖拽的目录节点；dragDone 时清空。 */
    private TOCReference dragSource;

    /** FXML 加载后由父控制器注入运行时依赖；必须在任何 onAction 触发前完成。 */
    public void bind(BookContext ctx, BookAction beginChange, StatusSink status, WarningSink warner) {
        this.ctx = ctx;
        this.beginChange = beginChange;
        this.status = status;
        this.warner = warner;
    }

    public void setOnChapterSelected(Consumer<ChapterNode> onChapterSelected) {
        this.onChapterSelected = onChapterSelected == null ? node -> {} : onChapterSelected;
    }

    /** 初始化 cell factory + 拖拽 + 选中监听；在 FXML 加载完成后调用。 */
    public void wire() {
        tocTree.setShowRoot(false);
        tocTree.setCellFactory(tree -> {
            TreeCell<ChapterNode> cell = new TreeCell<>() {
                @Override
                protected void updateItem(ChapterNode item, boolean empty) {
                    super.updateItem(item, empty);
                    setText(empty || item == null ? null : item.displayTitle());
                }
            };
            attachRenameHandler(cell);
            attachDragHandlers(cell);
            attachContextMenu(cell);
            return cell;
        });
        attachTreeDropHandlers();
        attachKeyboardShortcuts();
        tocTree.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, selected) -> {
            if (!ctx.loading()) {
                onChapterSelected.accept(selected == null ? null : selected.getValue());
            }
        });
    }

    // ---- FXML 入口 ----

    public void onAddChapter() {
        beginChange.run();
        String title = "第 " + (ctx.book().spine().size() + 1) + " 章";
        Resource chapter = ctx.book().addChapter(title, null);
        markDirty();
        refresh();
        selectResource(chapter);
        status.setStatus("已添加章节：" + title);
    }

    public void onDeleteChapter() {
        ChapterNode node = ctx.currentNode();
        if (node == null || node.resource() == null) {
            warner.warn("请先在目录中选择要删除的章节");
            return;
        }
        Resource target = node.resource();
        String title = node.displayTitle();
        beginChange.run();
        // 走 Book.removeResource：它按 href 清理目录节点，并同步 spine 与封面引用。
        // 自己按「资源解析结果」删目录是删不掉的——资源一旦先移除，解析结果就变成 null 了。
        ctx.book().removeResource(target);

        ctx.setCurrentNode(null);
        markDirty();
        refresh();
        status.setStatus("已删除章节：" + title);
    }

    public void onMoveUp() {
        moveChapter(-1);
    }

    public void onMoveDown() {
        moveChapter(1);
    }

    public void onRenameChapter() {
        TreeItem<ChapterNode> selected = tocTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null || selected.getValue().reference() == null) {
            warner.warn("请先在目录中选择要重命名的章节");
            return;
        }
        renameChapter(selected.getValue());
    }

    // ---- 目录树刷新 ----

    public void refresh() {
        TreeItem<ChapterNode> toSelect;
        ctx.setLoading(true);
        try {
            TreeItem<ChapterNode> root = new TreeItem<>(new ChapterNode("目录", null, null));
            for (TOCReference reference : ctx.book().toc().roots()) {
                root.getChildren().add(buildTreeItem(reference));
            }
            root.setExpanded(true);
            ChapterNode previous = ctx.currentNode();
            tocTree.setRoot(root);

            toSelect = previous == null || previous.resource() == null
                    ? null : findByResource(root, previous.resource());
            if (toSelect == null && !root.getChildren().isEmpty()) {
                toSelect = root.getChildren().get(0);
            }
        } finally {
            ctx.setLoading(false);
        }
        if (toSelect != null) {
            tocTree.getSelectionModel().select(toSelect);
            if (ctx.currentNode() == null) {
                onChapterSelected.accept(toSelect.getValue());
            }
        } else {
            onChapterSelected.accept(null);
        }
    }

    /** 在目录树中选中该资源对应的节点；不在目录里时返回 false。 */
    public boolean selectResource(Resource resource) {
        TreeItem<ChapterNode> root = tocTree.getRoot();
        if (root == null) {
            return false;
        }
        TreeItem<ChapterNode> item = findByResource(root, resource);
        if (item == null) {
            return false;
        }
        tocTree.getSelectionModel().select(item);
        return true;
    }

    // ---- 内部 ----

    private TreeItem<ChapterNode> buildTreeItem(TOCReference reference) {
        Resource resource = resolveResource(reference);
        TreeItem<ChapterNode> item = new TreeItem<>(new ChapterNode(reference.title(), resource, reference));
        for (TOCReference child : reference.children()) {
            item.getChildren().add(buildTreeItem(child));
        }
        item.setExpanded(true);
        return item;
    }

    private Resource resolveResource(TOCReference reference) {
        return ctx.book().resources().getByHref(Hrefs.resolve(ctx.book().contentDirectory(), reference.resourceHref()));
    }

    private TreeItem<ChapterNode> findByResource(TreeItem<ChapterNode> parent, Resource resource) {
        for (TreeItem<ChapterNode> child : parent.getChildren()) {
            if (resource.equals(child.getValue().resource())) {
                return child;
            }
            TreeItem<ChapterNode> nested = findByResource(child, resource);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private void renameChapter(ChapterNode node) {
        TextInputDialog dialog = new TextInputDialog(node.displayTitle());
        dialog.setTitle("重命名章节");
        dialog.setHeaderText(null);
        dialog.setContentText("章节标题：");
        if (ctx.stage() != null) {
            dialog.initOwner(ctx.stage());
        }
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        String newTitle = result.get().trim();
        if (newTitle.isEmpty() || newTitle.equals(node.displayTitle())) {
            return;
        }
        beginChange.run();
        node.reference().setTitle(newTitle);
        syncChapterTitle(node.resource(), newTitle);
        markDirty();
        refresh();
        status.setStatus("已重命名为：" + newTitle);
    }

    /** 把章节 XHTML 里的 {@code <title>} 与首个 {@code <h1>} 同步为新标题。 */
    private void syncChapterTitle(Resource chapter, String newTitle) {
        if (chapter == null || !chapter.isText()) {
            return;
        }
        String xhtml = chapter.asString();
        String updated = TextSearch.replaceFirstTagText(xhtml, "title", newTitle);
        updated = TextSearch.replaceFirstTagText(updated, "h1", newTitle);
        if (!updated.equals(xhtml)) {
            chapter.setString(updated);
        }
    }

    private void markDirty() {
        ctx.setDirty(true);
    }

    /**
     * 在目录里与相邻兄弟交换位置，阅读顺序随后由目录派生。
     *
     * <p>早先的实现只交换 spine 与「顶层目录项」：对嵌套章节（子条目）而言，spine 动了而
     * 目录没动，两者立刻分叉，校验会报 C09「目录顺序与阅读顺序不一致」。这里统一在节点
     * 所在的兄弟列表里交换，顶层与嵌套层级行为一致。
     */
    private void moveChapter(int delta) {
        ChapterNode node = ctx.currentNode();
        if (node == null || node.reference() == null) {
            warner.warn("请先在目录中选择要移动的章节");
            return;
        }
        TocEditor.Location location = TocEditor.locate(ctx.book(), node.reference());
        if (location == null) {
            warner.warn("该章节还没有加入目录，无法调整顺序");
            return;
        }
        int target = location.index() + delta;
        if (target < 0 || target >= location.siblings().size()) {
            status.setStatus(delta < 0 ? "已经是同级中的第一个" : "已经是同级中的最后一个");
            return;
        }
        beginChange.run();
        Collections.swap(location.siblings(), location.index(), target);
        TocEditor.syncSpineFromToc(ctx.book());

        markDirty();
        refresh();
        selectResource(node.resource());
        status.setStatus(delta < 0 ? "章节已上移" : "章节已下移");
    }

    // ---- 拖拽 ----

    /** 双击目录项进入重命名。 */
    private void attachRenameHandler(TreeCell<ChapterNode> cell) {
        cell.setOnMouseClicked(event -> {
            if (event.getClickCount() != 2) {
                return;
            }
            ChapterNode node = cell.getItem();
            if (node == null || node.reference() == null) {
                return;
            }
            event.consume();
            renameChapter(node);
        });
    }

    private enum DropHint {
        BEFORE, AFTER, INSIDE
    }

    private void attachDragHandlers(TreeCell<ChapterNode> cell) {
        cell.setOnDragDetected(event -> {
            ChapterNode node = cell.getItem();
            if (node == null || node.reference() == null) {
                return;
            }
            dragSource = node.reference();
            Dragboard board = cell.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(node.displayTitle());
            board.setContent(content);
            event.consume();
        });

        cell.setOnDragOver(event -> {
            // 无论是否允许放置都要 consume：否则事件会冒泡到 TreeView 的落点处理器，
            // 把「落在某一行上」误判成「落在空白处」，进而允许一次本不合法的移动
            event.consume();
            ChapterNode target = cell.getItem();
            if (!dropAllowed(target)) {
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            paintDropHint(cell, dropHint(cell, event.getY()));
        });

        cell.setOnDragExited(event -> cell.setStyle(null));

        cell.setOnDragDropped(event -> {
            ChapterNode target = cell.getItem();
            if (!dropAllowed(target)) {
                // 非法落点（自身或自己的子孙）：就地判否并结束事件。
                // 让它冒泡到 TreeView 会被当成「移到顶层末尾」，章节会被意外搬走
                event.setDropCompleted(false);
                event.consume();
                return;
            }
            DropHint hint = dropHint(cell, event.getY());
            TOCReference targetReference = target.reference();
            String title = dragSource.title();
            beginChange.run();
            boolean moved = switch (hint) {
                case BEFORE -> TocEditor.moveBefore(ctx.book(), dragSource, targetReference);
                case AFTER -> TocEditor.moveAfter(ctx.book(), dragSource, targetReference);
                case INSIDE -> TocEditor.moveTo(ctx.book(), dragSource, targetReference,
                        targetReference.children().size());
            };
            event.setDropCompleted(moved);
            event.consume();
            cell.setStyle(null);
            if (moved) {
                markDirty();
                refresh();
                status.setStatus("已移动章节：" + title);
            } else {
                status.setStatus("无法移动到该位置");
            }
            dragSource = null;
        });

        cell.setOnDragDone(event -> dragSource = null);
    }

    /** 目录树空白处：拖到此处表示移到顶层末尾。 */
    private void attachTreeDropHandlers() {
        tocTree.setOnDragOver(event -> {
            if (dragSource == null) {
                return;
            }
            event.acceptTransferModes(TransferMode.MOVE);
            event.consume();
        });
        tocTree.setOnDragDropped(event -> {
            if (dragSource == null) {
                return;
            }
            String title = dragSource.title();
            beginChange.run();
            boolean moved = TocEditor.moveToRoot(ctx.book(), dragSource);
            event.setDropCompleted(moved);
            event.consume();
            if (moved) {
                markDirty();
                refresh();
                status.setStatus("已移动到顶层末尾：" + title);
            }
            dragSource = null;
        });
    }

    private boolean dropAllowed(ChapterNode target) {
        return dragSource != null
                && target != null
                && target.reference() != null
                && dragSource != target.reference()
                && !TocEditor.isAncestorOrSelf(dragSource, target.reference());
    }

    private static DropHint dropHint(TreeCell<ChapterNode> cell, double y) {
        double height = cell.getHeight();
        if (height <= 0) {
            return DropHint.INSIDE;
        }
        if (y < height * 0.28) {
            return DropHint.BEFORE;
        }
        if (y > height * 0.72) {
            return DropHint.AFTER;
        }
        return DropHint.INSIDE;
    }

    private static void paintDropHint(TreeCell<ChapterNode> cell, DropHint hint) {
        cell.setStyle(switch (hint) {
            case BEFORE -> "-fx-border-color: -epubra-accent; -fx-border-width: 2 0 0 0;";
            case AFTER -> "-fx-border-color: -epubra-accent; -fx-border-width: 0 0 2 0;";
            case INSIDE -> "-fx-background-color: -epubra-selected-bg;";
        });
    }

    // ---- 右键菜单 ----

    /**
     * 给每个单元格挂一个 ContextMenu：添加 / 重命名 / 上移 / 下移 / 删除。
     *
     * <p>关键细节：右键时先把单元格选中——若不预先选中，菜单操作的 {@code ctx.currentNode()}
     * 仍是旧选中节点，会出现「右键 B 实际删 A」的错位。JavaFX 的 MenuItem 没有「目标参数」
     * 概念，最简单的修正是先 select 再弹菜单。
     *
     * <p>菜单项 disable 绑 {@code cell.itemProperty().isNull()}——空单元格（行尾、占位）不
     * 弹可点击的菜单。
     */
    private void attachContextMenu(TreeCell<ChapterNode> cell) {
        MenuItem addItem = menuItem("添加章节", e -> onAddChapter());
        MenuItem renameItem = menuItem("重命名", e -> onRenameChapter());
        MenuItem upItem = menuItem("上移", e -> onMoveUp());
        MenuItem downItem = menuItem("下移", e -> onMoveDown());
        MenuItem deleteItem = menuItem("删除", e -> onDeleteChapter());

        addItem.disableProperty().bind(cell.itemProperty().isNull());
        renameItem.disableProperty().bind(cell.itemProperty().isNull());
        upItem.disableProperty().bind(cell.itemProperty().isNull());
        downItem.disableProperty().bind(cell.itemProperty().isNull());
        deleteItem.disableProperty().bind(cell.itemProperty().isNull());

        // 右键时先把被点击单元格选中——再弹菜单，避免操作错位
        cell.setOnContextMenuRequested(event -> {
            TreeItem<ChapterNode> item = cell.getTreeItem();
            if (item != null) {
                tocTree.getSelectionModel().select(item);
            }
        });

        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(addItem, renameItem,
                new SeparatorMenuItem(), upItem, downItem,
                new SeparatorMenuItem(), deleteItem);
        cell.setContextMenu(menu);
    }

    private static MenuItem menuItem(String text, javafx.event.EventHandler<javafx.event.ActionEvent> handler) {
        MenuItem item = new MenuItem(text);
        item.setOnAction(handler);
        return item;
    }

    // ---- 键盘快捷键 ----

    /**
     * 给目录树挂键盘快捷键（仅在 TreeView 持有焦点时生效，避免与全局 accelerator 抢键）：
     * <ul>
     *   <li>{@code Delete} → 删除选中章节</li>
     *   <li>{@code Insert} → 在选中节点后插入新章节</li>
     *   <li>{@code Alt+↑} / {@code Alt+↓} → 上移 / 下移</li>
     * </ul>
     *
     * <p>{@code F2}（重命名）已有 FXML 全局 accelerator；TreeView 默认会把方向键用于导航，
     * 因此移动用 {@code Alt} 修饰避开冲突。{@code Insert} 与 {@code Delete} 走 TreeView
     * 默认不消费的键，无需修饰。
     */
    private void attachKeyboardShortcuts() {
        tocTree.setOnKeyPressed(this::handleTreeKey);
    }

    private void handleTreeKey(KeyEvent event) {
        if (tocTree.getSelectionModel().getSelectedItem() == null) {
            return;
        }
        KeyCode code = event.getCode();
        boolean consumed = switch (code) {
            case DELETE -> { onDeleteChapter(); yield true; }
            case INSERT -> { onAddChapter(); yield true; }
            case UP -> {
                if (event.isAltDown()) {
                    onMoveUp();
                    yield true;
                }
                yield false;
            }
            case DOWN -> {
                if (event.isAltDown()) {
                    onMoveDown();
                    yield true;
                }
                yield false;
            }
            default -> false;
        };
        if (consumed) {
            event.consume();
        }
    }
}