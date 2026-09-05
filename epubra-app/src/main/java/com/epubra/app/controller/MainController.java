package com.epubra.app.controller;

import com.epubra.app.EpubraApp;
import com.epubra.app.support.BookContext;
import com.epubra.app.support.BookHistory;
import com.epubra.app.support.PreviewHtml;
import com.epubra.app.support.TextSearch;
import com.epubra.app.support.Theme;
import com.epubra.app.support.ThemeManager;
import com.epubra.epublib.domain.Book;
import com.epubra.epublib.domain.Resource;
import com.epubra.epublib.io.EpubReader;
import com.epubra.epublib.io.EpubWriter;
import com.epubra.epublib.validation.EpubValidator;
import com.epubra.epublib.validation.ValidationIssue;
import com.epubra.epublib.validation.ValidationReport;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

import java.nio.file.Path;

/**
 * 主窗口控制器：目录浏览、章节编辑、元数据维护与 EPUB 存取。
 *
 * <p>本轮重构（Task S3a）：把原本散落的 30+ 业务字段全部下沉到 {@link BookContext}，
 * 本类保留纯 UI 与编排职责。后续 Sprint 将按职责进一步拆出 {@code UndoController}、
 * {@code DocumentController} 等子控制器。
 */
public class MainController {

    @FXML
    private TextArea contentArea;
    @FXML
    private WebView previewView;
    @FXML
    private TabPane editorTabs;

    @FXML
    private ToggleGroup activityGroup;
    @FXML
    private ToggleButton tocActivityButton;
    @FXML
    private ToggleButton resourceActivityButton;
    @FXML
    private ToggleButton metadataActivityButton;
    @FXML
    private ToggleButton validationActivityButton;

    // 侧边栏三个视图与底部面板：fx:include 根节点注入（类型即各子 FXML 的根元素类型）。
    // 与之成对的子控制器字段（<fx:id>Controller 命名规则）在下方。
    @FXML
    private VBox tocView;
    @FXML
    private VBox resourceView;
    @FXML
    private ScrollPane metadataView;
    @FXML
    private VBox bottomPanel;
    @FXML
    private HBox findBar;

    // fx:include 自动注入的子控制器：节点已由各自 FXML 绑定，
    // ctx 与回调在本类 initialize() 阶段统一 bind。
    @FXML
    private TocController tocViewController;
    @FXML
    private ResourceController resourceViewController;
    @FXML
    private MetadataViewController metadataViewController;
    @FXML
    private FindController findBarController;
    @FXML
    private ValidationController bottomPanelController;

    @FXML
    private MenuItem problemsItem;

    @FXML
    private MenuItem undoItem;
    @FXML
    private MenuItem redoItem;

    @FXML
    private Label statusLabel;
    @FXML
    private Label errorStatusLabel;
    @FXML
    private Label warningStatusLabel;
    @FXML
    private Label chapterStatusLabel;
    @FXML
    private Label wordStatusLabel;
    @FXML
    private Label themeStatusLabel;

    @FXML
    private RadioMenuItem themeLightItem;
    @FXML
    private RadioMenuItem themeDarkItem;
    @FXML
    private RadioMenuItem themeSepiaItem;

    /** 跨控制器共享状态：原本散落的字段全部下沉到这里。 */
    private final BookContext ctx = new BookContext();

    private final EpubReader reader = new EpubReader();
    private final EpubWriter writer = new EpubWriter();
    private final EpubValidator validator = new EpubValidator();
    private UndoController undoController;
    private DocumentController documentController;
    private SidebarController sidebarController;

    /** 当前主题。initialize 时取自持久化配置，切换后预览区与整个界面同步换色。 */
    private Theme currentTheme = Theme.LIGHT;

    public void setStage(Stage stage) {
        ctx.setStage(stage);
    }

    @FXML
    public void initialize() {
        // 子控制器由 fx:include 实例化（先于本方法执行 @FXML 注入），这里统一注入
        // BookContext 与回调。SidebarController 横跨活动栏 / 三个视图 / 底部面板多个
        // FXML 文件，无法归属某个子 FXML，保持手动构造。
        sidebarController = new SidebarController(
                activityGroup,
                tocActivityButton, resourceActivityButton,
                metadataActivityButton, validationActivityButton,
                tocView, resourceView, metadataView,
                bottomPanel);
        sidebarController.setupActivityBarInteraction();

        tocViewController.bind(ctx, this::beginChange, this::setStatus, this::warn);
        tocViewController.wire();
        tocViewController.setOnChapterSelected(this::showChapter);

        sidebarController.setupDefault();
        bindProblemsAccelerator();

        bottomPanelController.bind(ctx, validator, editorTabs, contentArea,
                tocViewController, sidebarController,
                this::commitPendingEdits, this::setStatus);
        bottomPanelController.setupTable();

        metadataViewController.bind(ctx, this::recordBeforeChange, this::markDirty,
                this::refreshAll, this::setStatus);

        resourceViewController.bind(ctx, editorTabs, contentArea,
                this::beginChange, this::markDirty,
                this::refreshAll, this::refreshResources,
                this::updateStatus, this::setStatus, this::warn,
                this::confirmDiscardChanges, this::showError);

        findBarController.bind(ctx, contentArea,
                this::beginChange, this::markDirty,
                this::reloadEditor, this::refreshPreview,
                this::setStatus, this::confirmDiscardChanges);

        contentArea.textProperty().addListener((obs, oldValue, text) -> {
            if (ctx.loading()) {
                return;
            }
            // 一段连续输入只在第一次击键时记录一次快照（此时 book 还是变更前的状态）
            ensureUndoController();
            undoController.onTextInput();
            markDirty();
        });

        subscribeAppEvents();

        currentTheme = ThemeManager.current();
        selectThemeItem(currentTheme);
        applyThemeWhenSceneReady();

        ensureDocumentController();
        documentController.newBook();
    }

    /**
     * 集中订阅 {@link com.epubra.app.support.AppEventBus}，把"状态变了 → 调 xxx"的入口
     * 全部从手动回调改为事件订阅。新增子控制器后只需追加订阅，不必改 MainController 主流程。
     */
    private void subscribeAppEvents() {
        ctx.bus().subscribe(com.epubra.app.support.AppEventBus.BookLoadedEvent.class,
                e -> refreshAll());
        ctx.bus().subscribe(com.epubra.app.support.AppEventBus.BookRestoredEvent.class,
                e -> refreshAll());
        ctx.bus().subscribe(com.epubra.app.support.AppEventBus.BookSavedEvent.class,
                e -> updateTitleAndHistory());
        ctx.bus().subscribe(com.epubra.app.support.AppEventBus.BookDirtyChangedEvent.class,
                e -> updateTitleAndHistory());
    }

    /** 集中更新标题栏与撤销菜单可用态；保存与脏标记均触发同一组 UI 重画。 */
    private void updateTitleAndHistory() {
        updateTitle();
        updateHistoryControls();
    }

    // ------------------------------------------------------------------ 文件

    @FXML
    public void onNew() {
        ensureDocumentController();
        documentController.onNew();
    }

    @FXML
    public void onOpen() {
        ensureDocumentController();
        documentController.onOpen();
    }

    @FXML
    public void onSave() {
        ensureDocumentController();
        documentController.onSave();
    }

    @FXML
    public void onSaveAs() {
        ensureDocumentController();
        documentController.onSaveAs();
    }

    @FXML
    public void onExit() {
        ensureDocumentController();
        documentController.onExit(ctx.stage()::close);
    }

    @FXML
    public void onAbout() {
        ensureDocumentController();
        documentController.onAbout();
    }

    private void ensureDocumentController() {
        if (documentController == null) {
            documentController = new DocumentController(ctx,
                    this::setStatus,
                    this::confirmDiscardChanges,
                    DocumentController.defaultDialogs(ctx.stage()),
                    this::reportError);
        }
    }

    /** 错误信息直接打到状态栏。复杂场景会让 DocumentController 触发 Alert，这里保持简洁。 */
    private void reportError(String message) {
        setStatus(message);
    }

    // ------------------------------------------------------------------ 撤销 / 重做

    @FXML
    public void onUndo() {
        ensureUndoController();
        undoController.undo();
    }

    @FXML
    public void onRedo() {
        ensureUndoController();
        undoController.redo();
    }

    /**
     * 记录一次变更「之前」的状态，并结束当前的输入编辑步。
     *
     * <p>必须在真正修改 {@link Book} 之前调用：它会先把正文与元数据写回，再拍快照，
     * 之后本次操作引起的界面文本变更不再重复计入历史。
     */
    private void beginChange() {
        ensureUndoController();
        undoController.beginChange();
    }

    /**
     * 记录一次「界面文本即将写回书籍之前」的状态。
     *
     * <p>{@link #beginChange()} 的顺序是先写回、再快照，适用于「先改结构」的操作；
     * 元数据这类「界面文本本身就是变更内容」的操作必须反过来，否则快照里已经是新值。
     */
    private void recordBeforeChange() {
        ensureUndoController();
        undoController.recordBeforeChange();
    }

    private void commitPendingEdits() {
        ensureUndoController();
        undoController.commitPendingEdits();
    }

    private void ensureUndoController() {
        if (undoController == null) {
            undoController = new UndoController(ctx, this::setStatus, this::clearValidationResults);
            undoController.installFlushCallbacks(this::flushCurrentChapter, this::flushMetadata);
        }
    }

    // ------------------------------------------------------------------ 章节

    @FXML
    public void onAddChapter() {
        tocViewController.onAddChapter();
    }

    @FXML
    public void onDeleteChapter() {
        tocViewController.onDeleteChapter();
    }

    @FXML
    public void onMoveUp() {
        tocViewController.onMoveUp();
    }

    @FXML
    public void onMoveDown() {
        tocViewController.onMoveDown();
    }

    @FXML
    public void onRenameChapter() {
        tocViewController.onRenameChapter();
    }



    @FXML
    public void onRefreshPreview() {
        flushCurrentChapter();
        refreshPreview();
        setStatus("预览已刷新");
    }

    // ------------------------------------------------------------------ 主题

    @FXML
    public void onThemeLight() {
        switchTheme(Theme.LIGHT);
    }

    @FXML
    public void onThemeDark() {
        switchTheme(Theme.DARK);
    }

    @FXML
    public void onThemeSepia() {
        switchTheme(Theme.SEPIA);
    }

    /** 切换主题：落盘偏好、换根节点样式类，并让预览区跟着换配色。 */
    private void switchTheme(Theme theme) {
        if (theme == currentTheme) {
            return;
        }
        currentTheme = theme;
        ThemeManager.save(theme);
        ThemeManager.apply(statusLabel.getScene(), theme);
        refreshPreview();
        if (themeStatusLabel != null) {
            themeStatusLabel.setText(theme.displayName());
        }
        setStatus("已切换到" + theme.displayName() + "主题");
    }

    /**
     * 在 initialize 阶段先把主题记下来，等 Scene 挂上再真正应用。
     *
     * <p>FXML 加载时 Scene 尚未创建，此时拿不到根节点，只能借 statusLabel 的
     * sceneProperty 做一次性回调。
     */
    private void applyThemeWhenSceneReady() {
        Scene scene = statusLabel.getScene();
        if (scene != null) {
            ThemeManager.apply(scene, currentTheme);
            return;
        }
        statusLabel.sceneProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Scene> observable, Scene oldScene, Scene newScene) {
                if (newScene == null) {
                    return;
                }
                statusLabel.sceneProperty().removeListener(this);
                ThemeManager.apply(newScene, currentTheme);
                refreshPreview();
            }
        });
    }

    /** 让单选菜单项的选中态与当前主题一致；setSelected 不触发 onAction，不会递归。 */
    private void selectThemeItem(Theme theme) {
        RadioMenuItem target = switch (theme) {
            case DARK -> themeDarkItem;
            case SEPIA -> themeSepiaItem;
            case LIGHT -> themeLightItem;
        };
        // FXML 里万一漏了某个菜单项，宁可只是不高亮，也不要让整个界面起不来
        if (target != null) {
            target.setSelected(true);
        }
        if (themeStatusLabel != null) {
            themeStatusLabel.setText(theme.displayName());
        }
    }

    // ------------------------------------------------------------------ 活动栏与侧边栏

/**
 * 给「问题面板」菜单项挂上 Ctrl+` 快捷键。
 *
 * <p>放在 controller 而不是 FXML：{@code KeyCombination} 对反引号的解析在不同实现下并不可靠，
 * 直接用 {@link KeyCode#BACK_QUOTE} 构造最稳。
 */
private void bindProblemsAccelerator() {
    if (problemsItem != null) {
        problemsItem.setAccelerator(
                new KeyCodeCombination(KeyCode.BACK_QUOTE, KeyCombination.CONTROL_DOWN));
    }
}

    /**
     * 处理活动栏「目录 / 资源 / 元数据」三类侧边栏切换。
     *
     * <p>复用了 {@link SidebarController#isCollapsingClick} 快照来判断「再点同一按钮」——
     * JavaFX 的 {@code ToggleGroup} 不允许已选中的按钮因再次点击而取消选中，仅靠
     * {@code onAction} 里读 {@code isSelected()} 无法区分首次点击与重复点击，
     * 而 {@code onMousePressed} 在 toggle 逻辑之前触发,按下瞬间的快照能可靠地反映用户意图。
     */
    private void toggleSideView(ToggleButton button, Runnable show) {
        if (sidebarController.isCollapsingClick(button)) {
            sidebarController.hideAllSideViews();
            button.setSelected(false);
            return;
        }
        show.run();
    }

    @FXML
    public void onShowTocView() {
        toggleSideView(tocActivityButton, () -> sidebarController.showTocView());
    }

    @FXML
    public void onShowResourceView() {
        toggleSideView(resourceActivityButton, () -> sidebarController.showResourceView());
    }

    @FXML
    public void onShowMetadataView() {
        toggleSideView(metadataActivityButton, () -> sidebarController.showMetadataView());
    }

    /**
     * 活动栏「校验」按钮：选中时展开底部面板并立即校验，取消选中时收起面板、
     * 把活动栏交还给上一个侧边视图（避免活动栏出现「一个都没选中」的空档）。
     *
     * <p>侧边栏本身不切换——校验结果在底部面板，目录 / 资源 / 元数据保持用户离开时的样子。
     */
    @FXML
    public void onShowProblems() {
        if (validationActivityButton.isSelected()) {
            sidebarController.showProblems(bottomPanelController::run);
            return;
        }
        sidebarController.hideProblems();
    }

    /**
     * 「视图 → 问题面板」：面板与活动栏按钮一起切换，快捷键 Ctrl+`。
     * <p>保持原入口：底部面板可见性、按钮选中与立即校验这些是同一个编排序列，
     * 与 {@link #onShowProblems} 不同的是这里按钮选中由 sidebar 内部同步。
     */
    @FXML
    public void onToggleProblems() {
        if (bottomPanel.isVisible()) {
            sidebarController.hideProblems();
            return;
        }
        sidebarController.showProblems(bottomPanelController::run);
    }

    /** 底部面板头上的关闭按钮。 */
    @FXML
    public void onHideProblems() {
        sidebarController.hideProblems();
    }

    // 校验全部委托给 ValidationController：MainController 只保留入口与回调钩子。

    /**
     * 清空校验结果——{@link UndoController} 在撤销/重做、打开、新建时需要回调它。
     */
    private void clearValidationResults() {
        bottomPanelController.clear();
    }

    // ------------------------------------------------------------------ 元数据

    // ------------------------------------------------------------------ 校验

    /**
     * 跑一次校验并刷新问题面板。
     *
     * <p>校验是只读操作：只调 {@link #commitPendingEdits()} 把屏幕上的文本同步回 {@link Book}，
     * <b>不</b>调 {@link #beginChange()} / {@link #markDirty()}，因此不会在撤销栈里留下记录，
     * 也不会把「只是想看看有多少问题」变成一次未保存修改。
     *
     * <p>有真实磁盘文件走 {@code validate(Book, Path)}（含容器级规则），否则降级为纯内存校验。
     */
    @FXML
    public void onRunValidation() {
        bottomPanelController.run();
    }

    // 校验全部委托给 {@link ValidationController}：MainController 只保留入口与回调钩子。

    // 元数据面板的全部逻辑（表单读写 / 应用修改 / 撤销快照前的写回）已迁至
    // MetadataViewController，由 metadata-view.fxml 直接绑定；本类只在
    // refreshAll / UndoController 的 flush 回调里调它的 loadIntoFields / flush。

    // ------------------------------------------------------------------ 查找 / 替换

    @FXML
    public void onShowFind() {
        findBarController.showBar();
    }

    @FXML
    public void onCloseFind() {
        findBarController.closeBar();
    }

    @FXML
    public void onFindNext() {
        findBarController.findNext();
    }

    @FXML
    public void onFindPrevious() {
        findBarController.findPrevious();
    }

    @FXML
    public void onReplaceOne() {
        findBarController.replaceOne();
    }

    @FXML
    public void onReplaceAll() {
        findBarController.replaceAll();
    }

    // 查找与替换的全部实现已迁出到 FindController；MainController 至此只保留 1 行委托入口。


    // ------------------------------------------------------------------ 资源

    @FXML
    public void onImportResources() {
        resourceViewController.importResources();
    }

    @FXML
    public void onExportResource() {
        resourceViewController.exportSelected();
    }

    @FXML
    public void onDeleteResource() {
        resourceViewController.deleteSelected();
    }

    @FXML
    public void onSetCover() {
        resourceViewController.setCoverFromSelected();
    }

    @FXML
    public void onInsertImage() {
        resourceViewController.insertSelectedImageIntoChapter();
    }

    @FXML
    public void onCleanupResources() {
        resourceViewController.cleanupUnused();
    }

    /** 刷新资源列表；nav 与 ncx 由写出流程自动维护，不展示给用户。委托 ResourceController。 */
    private void refreshResources() {
        resourceViewController.refresh();
    }

    // 目录树交互已迁出到 TocController，MainController 仅保留 showChapter 用于「章节被选中」回调。

    private void refreshAll() {
        ctx.invalidateWordCounts();
        ctx.setLoading(true);
        try {
            metadataViewController.loadIntoFields(ctx.book().metadata());
        } finally {
            ctx.setLoading(false);
        }
        refreshToc();
        refreshResources();
        updateStatus();
    }

    /** 目录树刷新转发；null 防护保留——bind 之前不会有刷新请求，但保持防御式。 */
    private void refreshToc() {
        if (tocViewController != null) {
            tocViewController.refresh();
        }
    }

    private void showChapter(ChapterNode node) {
        flushCurrentChapter();
        ctx.setCurrentNode(node);
        ctx.setLoading(true);
        try {
            if (node == null || node.resource() == null) {
                contentArea.clear();
                contentArea.setDisable(true);
            } else {
                contentArea.setDisable(false);
                contentArea.setText(node.resource().asString());
                contentArea.positionCaret(0);
            }
        } finally {
            ctx.setLoading(false);
        }
        refreshPreview();
        updateStatus();
    }

    /** 把当前章节资源的内容重新读回编辑器；用于内容被程序化修改后同步界面。 */
    private void reloadEditor() {
        ChapterNode current = ctx.currentNode();
        if (current == null || current.resource() == null || contentArea.isDisabled()) {
            return;
        }
        ctx.setLoading(true);
        try {
            contentArea.setText(current.resource().asString());
            contentArea.positionCaret(0);
        } finally {
            ctx.setLoading(false);
        }
    }

    private void refreshPreview() {
        ChapterNode current = ctx.currentNode();
        if (current == null || current.resource() == null) {
            previewView.getEngine().loadContent(PreviewHtml.emptyDocument(currentTheme));
            return;
        }
        // 预览区是 WebView，吃不到 -epubra-* 变量，改为往 XHTML 里注入一段内联主题样式
        previewView.getEngine().loadContent(
                PreviewHtml.withTheme(current.resource().asString(), currentTheme),
                "application/xhtml+xml");
    }

    /** 把编辑器中的内容写回当前章节资源。 */
    private void flushCurrentChapter() {
        ChapterNode current = ctx.currentNode();
        if (current == null || current.resource() == null) {
            return;
        }
        current.resource().setString(contentArea.getText());
        ctx.invalidateWordCounts();
    }

    /** 撤销快照回放前把元数据面板的当前值写回书籍；实现已迁 MetadataViewController。 */
    private void flushMetadata() {
        metadataViewController.flush();
    }

    // ------------------------------------------------------------------ 状态

    private boolean confirmDiscardChanges() {
        if (!ctx.dirty()) {
            return true;
        }
        return confirm("未保存的修改", "当前书籍有未保存的修改。\n继续操作将丢弃这些修改，是否继续？");
    }

    private boolean confirm(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(ctx.stage());
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void warn(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(ctx.stage());
        alert.showAndWait();
    }

    private void markDirty() {
        ctx.setDirty(true);
        updateStatus();
    }

    private void setStatus(String message) {
        statusLabel.setText(message);
    }

    private void updateStatus() {
        chapterStatusLabel.setText("章节 " + ctx.book().spineResources().size());
        wordStatusLabel.setText("字数 " + wordCount());
        updateIssueCounters();
        updateHistoryControls();
        updateTitle();
    }

    /** 状态栏的错误 / 警告计数，取自最近一次校验结果。 */
    private void updateIssueCounters() {
        if (errorStatusLabel != null) {
            errorStatusLabel.setText("错误 " + ctx.lastReport().errorCount());
        }
        if (warningStatusLabel != null) {
            warningStatusLabel.setText("警告 " + ctx.lastReport().warningCount());
        }
    }

    /**
     * 全书正文字数：各章节 XHTML 剥离标签后的非空白字符数之和。
     *
     * <p>状态栏在每次击键后都会刷新，因此逐章统计的结果按资源缓存起来，只有当前正在编辑的
     * 那一章实时统计（编辑器里尚未写回的输入也要计入）。缓存由 {@link BookContext#invalidateWordCounts()}
     * 在内容被程序化改写或换书时整体失效。
     */
    private int wordCount() {
        Resource current = ctx.currentNode() == null ? null : ctx.currentNode().resource();
        int total = 0;
        for (Resource chapter : ctx.book().spineResources()) {
            if (chapter == current && !contentArea.isDisabled()) {
                total += TextSearch.plainTextLength(contentArea.getText());
                continue;
            }
            total += ctx.wordCounts().computeIfAbsent(chapter, resource -> TextSearch.plainTextLength(resource.asString()));
        }
        return total;
    }

    private void updateHistoryControls() {
        boolean canUndo = ctx.history().canUndo();
        boolean canRedo = ctx.history().canRedo();
        if (undoItem != null) {
            undoItem.setDisable(!canUndo);
        }
        if (redoItem != null) {
            redoItem.setDisable(!canRedo);
        }
    }

    private void updateTitle() {
        if (ctx.stage() == null) {
            return;
        }
        String name = ctx.currentFile() == null ? "新书籍" : ctx.currentFile().getFileName().toString();
        ctx.stage().setTitle(EpubraApp.APP_NAME + " - " + name + (ctx.dirty() ? " *" : ""));
    }

    private void showError(String title, String message, Exception e) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(message);
        alert.setContentText(e.getMessage());
        alert.initOwner(ctx.stage());
        alert.showAndWait();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}