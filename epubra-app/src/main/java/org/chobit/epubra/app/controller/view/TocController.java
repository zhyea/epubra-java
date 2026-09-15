package org.chobit.epubra.app.controller.view;

import org.chobit.epubra.app.ui.dialog.ChapterSplitDialog;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.editor.ChapterSplitOps;
import org.chobit.epubra.app.editor.TextSearch;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.TOCReference;
import org.chobit.epubra.lib.domain.TocEditor;
import org.chobit.epubra.lib.util.Hrefs;
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
import javafx.stage.Stage;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * 目录树控制器：章节树渲染（支持多级嵌套）、选中同步、拖拽排序、层级升降与重命名。
 *
 * <p>作为 {@code toc-view.fxml} 的 {@code fx:controller} 由 FXML 实例化：
 * 目录树节点经 {@code @FXML} 注入，{@link BookContext} 与回调在父控制器
 * {@code initialize()} 阶段通过 {@link #bind} 注入——FXML 加载时子控制器先于父构造，
 * 此时还不能触碰 ctx。因此本类不得定义 {@code initialize()} 方法。
 *
 * <p>层级结构本身由内核 {@link TocEditor} 维护（indent / outdent / removeKeepingChildren），
 * 本类只负责把用户操作转成这些调用，并在成功后重排阅读顺序（内核负责）与刷新树。
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

    /** 拖拽相关：onAction 操作（add/delete/move 等）走这里，UndoActivity 已在 MainController 持有。 */
    @FunctionalInterface
    public interface BookAction {
        void run();
    }


    private Consumer<ChapterNode> onChapterSelected = node -> {};

    /** 标记「当前变更需要进入撤销栈」。 */
    private BookAction beginChange;
    private Stage stage;
    private ChapterNode currentNode;

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

    /** 当前目录选中项属于目录 UI 状态，不放入 BookContext。 */
    public ChapterNode currentNode() {
        return currentNode;
    }

    /**
     * 由父控制器（{@code MainController.showChapter}）写入当前章节。
     * 与树选中监听互为兜底：选中监听覆盖「用户点树」，这里覆盖「程序切换章节」。
     */
    public void setCurrentNode(ChapterNode node) {
        this.currentNode = node;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    public void clearSelection() {
        currentNode = null;
        if (tocTree != null) {
            tocTree.getSelectionModel().clearSelection();
        }
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
                ChapterNode next = selected == null ? null : selected.getValue();
                // 先回调父控制器，让它把旧章节内容写回并加载新章节；
                // 回调内部会通过 setCurrentNode 更新当前节点。若调用方没有更新，
                // 这里再补一次，避免 currentNode 与 TreeView 脱节。
                onChapterSelected.accept(next);
                if (currentNode != next) {
                    currentNode = next;
                }
            }
        });
    }

    // ---- FXML 入口 ----

    public void onAddChapter() {
        // 守卫必须在 beginChange **之前**：先拍快照再退出会在历史里留下一条无对应变更的记录，
        // 用户按撤销会「什么都没变」。当前书架态下「章节」菜单是收起的（EditorShellActivity），
        // 所以这条路正常碰不到——但菜单显隐一旦调整、或该命令被快捷键 / 右键 / 自动化调到，
        // 就会把 NPE 抛到 FX 事件线程，表现为「按了没反应」。与 onDeleteChapter 同形守卫。
        if (ctx.book() == null) {
            warner.warn("请先打开或新建一本图书");
            return;
        }
        beginChange.run();
        String title = "第 " + (ctx.book().spine().size() + 1) + " 章";
        Resource chapter = ctx.book().addChapter(title, null);
        markDirty();
        refresh();
        selectResource(chapter);
        status.setStatus("已添加章节：" + title);
    }

    public void onDeleteChapter() {
        ChapterNode node = currentNode;
        if (node == null || node.resource() == null) {
            warner.warn("请先在目录中选择要删除的章节");
            return;
        }
        Resource target = node.resource();
        String title = node.displayTitle();
        int childCount = node.reference() == null ? 0 : node.reference().children().size();
        beginChange.run();
        // 顺序要紧：**先按「子章节提升为同级」摘除目录节点，再删资源**。反过来的话
        // removeResource 会先按 href 把目录节点整棵摘掉（Book.removeTocNodesByHref 只摘匹配项、
        // 不处理 children），子章节随即脱离目录却仍留在阅读顺序里 → 校验报 C09。
        if (node.reference() != null) {
            TocEditor.removeKeepingChildren(ctx.book(), node.reference());
        }
        // 走 Book.removeResource：它按 href 清理目录节点，并同步 spine 与封面引用。
        // 自己按「资源解析结果」删目录是删不掉的——资源一旦先移除，解析结果就变成 null 了。
        ctx.book().removeResource(target);

        currentNode = null;
        markDirty();
        refresh();
        status.setStatus(childCount == 0
                ? "已删除章节：" + title
                : "已删除章节：" + title + "，" + childCount + " 个子章节已提升为同级");
    }

    public void onMoveUp() {
        moveChapter(-1);
    }

    public void onMoveDown() {
        moveChapter(1);
    }

    /** 降一级：成为前一个同级章节的子章节。 */
    public void onIndentChapter() {
        changeLevel(true);
    }

    /** 升一级：成为父章节的下一个同级章节。 */
    public void onOutdentChapter() {
        changeLevel(false);
    }

    public void onRenameChapter() {
        TreeItem<ChapterNode> selected = tocTree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null || selected.getValue().reference() == null) {
            warner.warn("请先在目录中选择要重命名的章节");
            return;
        }
        renameChapter(selected.getValue());
    }

    /**
     * 章节拆分：对话框选「前后缀标记 / 正则 / 每段字数」三种方式之一，把当前章切成多章。
     *
     * <p>守卫（无书 / 无选中 / 不在目录）都放在 {@code beginChange} **之前**——先拍快照再
     * 退出会在撤销栈里留下无对应变更的空步（与 {@link #onAddChapter()} 同口径）。对话框取消
     * 与「找不到可拆分处」同样发生在快照之前，撤销栈不会被空转消耗。纯逻辑在
     * {@code ChapterSplitOps}（拆分 + 应用），本类只做装配与提示。
     */
    public void onSplitChapter() {
        ChapterNode node = currentNode;
        if (node == null || node.resource() == null) {
            warner.warn("请先在目录中选择要拆分的章节");
            return;
        }
        if (node.reference() == null) {
            warner.warn("该章节还没有加入目录，无法拆分");
            return;
        }
        String baseTitle = node.displayTitle();
        Optional<ChapterSplitOps.Params> chosen =
                ChapterSplitDialog.show(stage, baseTitle);
        if (chosen.isEmpty()) {
            return;
        }
        List<ChapterSplitOps.Segment> segments;
        try {
            segments = ChapterSplitOps.split(node.resource().asString(), chosen.get());
        } catch (IllegalArgumentException invalid) {
            warner.warn(invalid.getMessage());
            return;
        }
        if (segments.size() <= 1) {
            status.setStatus("未找到可拆分处：" + baseTitle);
            return;
        }
        beginChange.run();
        ChapterSplitOps.apply(ctx.book(), node.reference(), segments);
        markDirty();
        refresh();
        selectResource(node.resource());
        status.setStatus("已拆分为 " + segments.size() + " 章：" + baseTitle);
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
            ChapterNode previous = currentNode;
            tocTree.setRoot(root);

            toSelect = previous == null || previous.resource() == null
                    ? null : findByResource(root, previous.resource());
            if (toSelect == null && !root.getChildren().isEmpty()) {
                toSelect = root.getChildren().get(0);
            }
            // 在 loading 状态下完成选择，避免 TreeView 监听器先覆盖 currentNode，
            // 导致父控制器无法把旧编辑内容写回旧章节。
            if (toSelect != null) {
                tocTree.getSelectionModel().select(toSelect);
            } else {
                tocTree.getSelectionModel().clearSelection();
            }
        } finally {
            ctx.setLoading(false);
        }
        if (toSelect != null) {
            ChapterNode next = toSelect.getValue();
            // 不要在回调前覆盖 currentNode：MainController.showChapter 会先保存
            // currentNode 对应的旧编辑内容，随后再切换到 next。
            onChapterSelected.accept(next);
            if (currentNode != next) {
                currentNode = next;
            }
        } else {
            onChapterSelected.accept(null);
            currentNode = null;
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
        if (stage != null) {
            dialog.initOwner(stage);
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
        ChapterNode node = currentNode;
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

    /**
     * 层级调整：降一级 = 成为前一个同级章节的最后一个子章节；升一级 = 成为父章节的下一个同级章节。
     *
     * <p>与 {@link #moveChapter(int)} 同族：都在节点所在的层级内操作，成功后由内核
     * {@link TocEditor#syncSpineFromToc} 重排阅读顺序，因此嵌套章节不会与 spine 分叉。
     * 边界不满足时只给状态提示、不动结构——与上移/下移的容错方式保持一致。
     *
     * @param deeper true 降一级，false 升一级
     */
    private void changeLevel(boolean deeper) {
        ChapterNode node = currentNode;
        if (node == null || node.reference() == null) {
            warner.warn("请先在目录中选择要调整层级的章节");
            return;
        }
        TocEditor.Location location = TocEditor.locate(ctx.book(), node.reference());
        if (location == null) {
            warner.warn("该章节还没有加入目录，无法调整层级");
            return;
        }
        if (deeper && location.index() == 0) {
            status.setStatus("已经是同级中的第一个章节，无法降级");
            return;
        }
        if (!deeper && location.parent() == null) {
            status.setStatus("已经是顶层章节，无法升级");
            return;
        }
        beginChange.run();
        boolean changed = deeper
                ? TocEditor.indent(ctx.book(), node.reference())
                : TocEditor.outdent(ctx.book(), node.reference());
        if (!changed) {
            status.setStatus("无法调整层级");
            return;
        }
        Resource resource = node.resource();
        markDirty();
        refresh();
        if (resource != null) {
            selectResource(resource);
        }
        status.setStatus(deeper ? "章节已降一级" : "章节已升一级");
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
     * 给每个单元格挂一个 ContextMenu：添加 / 重命名 / 上移 / 下移 / 降一级 / 升一级 / 删除。
     *
     * <p>关键细节：右键时先把单元格选中——若不预先选中，菜单操作的当前目录节点
     * 仍是旧选中节点，会出现「右键 B 实际删 A」的错位。JavaFX 的 MenuItem 没有「目标参数」
     * 概念，最简单的修正是先 select 再弹菜单。
     *
     * <p>菜单项 disable 绑 {@code cell.itemProperty().isNull()}——空单元格（行尾、占位）不
     * 弹可点击的菜单。层级两项例外，见 {@link #updateLevelMenuState}。
     */
    private void attachContextMenu(TreeCell<ChapterNode> cell) {
        MenuItem addItem = menuItem("添加章节", e -> onAddChapter());
        MenuItem renameItem = menuItem("重命名", e -> onRenameChapter());
        MenuItem upItem = menuItem("上移", e -> onMoveUp());
        MenuItem downItem = menuItem("下移", e -> onMoveDown());
        MenuItem indentItem = menuItem("降一级", e -> onIndentChapter());
        MenuItem outdentItem = menuItem("升一级", e -> onOutdentChapter());
        MenuItem deleteItem = menuItem("删除", e -> onDeleteChapter());

        addItem.disableProperty().bind(cell.itemProperty().isNull());
        renameItem.disableProperty().bind(cell.itemProperty().isNull());
        upItem.disableProperty().bind(cell.itemProperty().isNull());
        downItem.disableProperty().bind(cell.itemProperty().isNull());
        deleteItem.disableProperty().bind(cell.itemProperty().isNull());
        // ⚠ 降级 / 升级两项**不能**用 disableProperty().bind(...)：可用性取决于树结构
        //（同级首项不可降级、顶层不可升级），静态表达式表达不了；而且属性一旦 bind，
        // 之后 setDisable 会抛「A bound value cannot be set」。改由弹出前逐次重算。

        // 右键时先把被点击单元格选中——再弹菜单，避免操作错位
        cell.setOnContextMenuRequested(event -> {
            TreeItem<ChapterNode> item = cell.getTreeItem();
            if (item != null) {
                tocTree.getSelectionModel().select(item);
            }
            updateLevelMenuState(indentItem, outdentItem,
                    item == null ? null : item.getValue());
        });

        ContextMenu menu = new ContextMenu();
        menu.getItems().addAll(addItem, renameItem,
                new SeparatorMenuItem(), upItem, downItem,
                new SeparatorMenuItem(), indentItem, outdentItem,
                new SeparatorMenuItem(), deleteItem);
        cell.setContextMenu(menu);
    }

    /**
     * 按目标节点刷新「降一级 / 升一级」的可用性：同级首项不可降级，顶层不可升级，空单元格两项都禁用。
     *
     * <p>包内可见是刻意的——测试可直接校验灰化规则，不必构造 {@code ContextMenuEvent}，
     * 也不必依赖 {@code TreeCell} 的 skin 是否已经建立。
     *
     * @param node 目标节点；{@code null} 表示空单元格（行尾占位）
     */
    void updateLevelMenuState(MenuItem indentItem, MenuItem outdentItem, ChapterNode node) {
        TOCReference reference = node == null ? null : node.reference();
        TocEditor.Location location = (reference == null || ctx == null || ctx.book() == null)
                ? null
                : TocEditor.locate(ctx.book(), reference);
        indentItem.setDisable(location == null || location.index() == 0);
        outdentItem.setDisable(location == null || location.parent() == null);
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
     *   <li>{@code Alt+←} / {@code Alt+→} → 升一级 / 降一级</li>
     * </ul>
     *
     * <p>{@code F2}（重命名）已有 FXML 全局 accelerator；TreeView 默认会把方向键用于导航与
     * 折叠/展开，因此移动与层级一律用 {@code Alt} 修饰避开冲突——水平方向不加修饰会被
     * TreeView 吃掉去折叠节点。{@code Insert} 与 {@code Delete} 走 TreeView 默认不消费的键，
     * 无需修饰。
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
            case LEFT -> {
                if (event.isAltDown()) {
                    onOutdentChapter();   // 向左 = 回到上一层
                    yield true;
                }
                yield false;
            }
            case RIGHT -> {
                if (event.isAltDown()) {
                    onIndentChapter();    // 向右 = 进入下一层
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
