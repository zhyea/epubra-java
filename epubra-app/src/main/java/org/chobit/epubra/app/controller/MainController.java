package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.EpubraApp;
import org.chobit.epubra.app.activities.DocumentActivity;
import org.chobit.epubra.app.activities.InsertActivity;
import org.chobit.epubra.app.activities.StatusCoordinator;
import org.chobit.epubra.app.activities.ThemeActivity;
import org.chobit.epubra.app.activities.UndoActivity;
import org.chobit.epubra.app.activities.WorkspaceActivity;
import org.chobit.epubra.app.controller.layout.SidebarController;
import org.chobit.epubra.app.controller.view.FindController;
import org.chobit.epubra.app.controller.view.MetadataViewController;
import org.chobit.epubra.app.controller.view.ResourceController;
import org.chobit.epubra.app.controller.view.TocController;
import org.chobit.epubra.app.controller.view.ValidationController;
import org.chobit.epubra.app.controller.view.WelcomePageController;
import org.chobit.epubra.app.context.Unsubscriber;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.app.ui.FxNodes;
import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.document.Autosave;
import org.chobit.epubra.app.document.AutosaveConfig;
import org.chobit.epubra.app.editor.PreviewHtml;
import org.chobit.epubra.app.editor.TextSearch;
import org.chobit.epubra.app.editor.Theme;
import org.chobit.epubra.app.platform.AppPaths;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;
import org.chobit.epubra.lib.validation.EpubValidator;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 主窗口控制器：目录浏览、章节编辑、元数据维护与 EPUB 存取。
 *
 * <p>本轮重构（Task S3a）：把原本散落的 30+ 业务字段全部下沉到 {@link BookContext}，
 * 本类保留纯 UI 与编排职责；文件流程、撤销流程等业务动作交给 activities 层。
 */
public class MainController {

    @FXML
    private TextArea contentArea;
    @FXML
    private WebView previewView;
    @FXML
    private TabPane editorTabs;
    /**
     * 并排预览容器：与 editorTabs 互斥显示，二者共用 contentArea / previewView 两个节点。
     */
    @FXML
    private SplitPane splitPreviewPane;

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
    /**
     * 侧边栏三个视图的公共容器（SplitPane item 0）。收起侧栏时必须连它一起隐藏，
     * 否则 SplitPane 仍按 dividerPositions 给它留 22% 宽度，编辑区伸不过去。
     */
    @FXML
    private StackPane sidePanel;
    /**
     * 主区左右分栏：侧栏容器 + 编辑区。
     */
    @FXML
    private SplitPane mainSplit;
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
    private WelcomePageController welcomePageController;
    // 欢迎页 FXML 的根节点：JavaFX fx:include 既会注入子控制器也会注入根节点。
    // 当前不直接使用，但保留以便后续需要整体替换 / 定位节点时不必再补字段。
    @SuppressWarnings("unused")
    @FXML
    private StackPane welcomePage;

    @FXML
    private MenuItem problemsItem;
    @FXML
    private MenuItem splitPreviewItem;

    @FXML
    private MenuItem undoItem;
    @FXML
    private MenuItem redoItem;

    @FXML
    private Label statusLabel;
    /**
     * 长操作进度条与标题标签——{@link AsyncTasks} 启动时显示、结束时自动隐藏。
     */
    @FXML
    private ProgressBar statusProgressBar;
    @FXML
    private Label statusProgressLabel;
    @FXML
    private Region statusProgressDivider;
    @FXML
    private Label errorStatusLabel;
    @FXML
    private Label warningStatusLabel;
    // 错误 / 警告标签后面各跟一条 1px 分隔竖线。计数为 0 时标签文本清空，这两条竖线必须
    // 同步隐藏——只清文本会留下「就绪  | |  章节 12 | …」这种孤立竖线的视觉噪音。
    @FXML
    private Region errorStatusDivider;
    @FXML
    private Region warningStatusDivider;
    @FXML
    private Label chapterStatusLabel;
    @FXML
    private Label wordStatusLabel;
    @FXML
    private Label chapterWordStatusLabel;
    @FXML
    private Label themeStatusLabel;
    @FXML
    private Label autosaveStatusLabel;

    @FXML
    private RadioMenuItem themeLightItem;
    @FXML
    private RadioMenuItem themeDarkItem;
    @FXML
    private RadioMenuItem themeSepiaItem;
    @FXML
    private Menu recentWorkspaceMenu;
    @FXML
    private VBox activityBar;
    @FXML
    private HBox statusBar;

    /**
     * 跨控制器共享状态：原本散落的字段全部下沉到这里。
     */
    /**
     * 主窗口 stage。由 {@code EpubraApp} 在 FXML 加载完成后注入——晚于 {@link #initialize()}，
     * 所以子控制器拿不到构造期参数，只能由 {@link #setStage(Stage)} 补发。
     */
    private Stage stage;

    private final BookContext ctx = new BookContext();

    private final EpubValidator validator = new EpubValidator();
    private UndoActivity undoActivity;
    private DocumentActivity documentActivity;
    private SidebarController sidebarController;

    /**
     * 事件总线订阅句柄：{@link #subscribeAppEvents()} 注册，主窗口关闭时由 {@link #dispose()} 统一退订。
     * 订阅期与主控制器一致（整个主窗口生命周期），必须长期持有、不能随方法返回被 close。
     */
    private final List<Unsubscriber> busSubscribers = new ArrayList<>();

    /**
     * 自动暂存的「停顿 N 秒后落盘」节流器。每次内容变更时调 {@link PauseTransition#playFromStart()}
     * 重置计时；计时器到点才真正写盘——避免每按一个键都 IO。
     *
     * <p>由 {@code ctx.autosaveConfig().debounceSeconds()} 驱动；外部禁用开关
     * {@code ctx.autosaveConfig().enabled() == false} 时干脆不挂监听（见 {@link #wireAutosave}）。
     */
    private PauseTransition autosaveDebounce;

    /**
     * 当前主题。initialize 时取自持久化配置，切换后预览区与整个界面同步换色。
     */
    private ThemeActivity themeActivity;
    private StatusCoordinator status;
    private InsertActivity insertActivity;
    private WorkspaceActivity workspaceActivity;

    /**
     * 编辑区呈现模式：{@code false} = 内容与预览分标签，{@code true} = 左右并排对照。
     */
    private boolean splitPreview = false;

    public void setStage(Stage stage) {
        this.stage = stage;
        // 子控制器在 initialize 阶段已 bind 完，此时只能补发 stage
        if (documentActivity != null) {
            documentActivity.setStage(stage);
        }
        if (tocViewController != null) {
            tocViewController.setStage(stage);
        }
        if (resourceViewController != null) {
            resourceViewController.setStage(stage);
        }
        if (metadataViewController != null) {
            metadataViewController.setStage(stage);
        }
        workspaceActivity.refreshRecentMenu();
    }

    /**
     * 当前章节属于<b>目录 UI 状态</b>，归 {@link TocController} 持有（BookContext 只管 Book 数据）。
     * 这里包一层做 null 防御：FXML 注入完成前的早期调用返回 null 而不是 NPE。
     */
    private ChapterNode currentChapter() {
        return tocViewController == null ? null : tocViewController.currentNode();
    }

    private void setCurrentChapter(ChapterNode node) {
        if (tocViewController != null) {
            tocViewController.setCurrentNode(node);
        }
    }

    @FXML
    public void initialize() {
        // WebView 缓存目录必须显式指定,否则 JavaFX native 会按 main class FQCN
        // 派生一个 ~/.Epubra/.org.chobit.epubra.app.EpubraApp/webview/ 子目录;
        // 公开 API setUserDataDirectory 覆盖默认行为,锁定到 AppPaths.webviewCacheDir()。
        // 必须在任何 loadContent/load 之前调用(否则 native 已创建默认目录,改不动了)。
        previewView.getEngine().setUserDataDirectory(AppPaths.webviewCacheDir().toFile());

        // status 必须先于任何 bind 构造：子控制器拿的是 status::set 这类方法引用，
        // 引用在求值时就要拿到非空实例，放到后面的 bind 之后再建会 NPE。
        status = new StatusCoordinator(ctx, contentArea, this::currentChapter, () -> stage,
                statusLabel,
                statusProgressBar, statusProgressLabel, statusProgressDivider,
                errorStatusLabel, errorStatusDivider,
                warningStatusLabel, warningStatusDivider,
                chapterStatusLabel, wordStatusLabel, chapterWordStatusLabel,
                undoItem, redoItem);

        insertActivity = new InsertActivity(contentArea, status::set, this::beginChange);

        workspaceActivity = new WorkspaceActivity(ctx, recentWorkspaceMenu, () -> stage,
                this::confirmDiscardChanges, this::warn,
                () -> setCurrentChapter(null),
                workspace -> {
                    welcomePageController.showWorkspace(workspace);
                    welcomePageController.show();
                },
                draft -> {
                    ensureDocumentActivity();
                    documentActivity.openDraftAsync(draft);
                },
                file -> {
                    ensureDocumentActivity();
                    documentActivity.openFileAsync(file);
                },
                () -> setEditorChromeVisible(false));

        // 子控制器由 fx:include 实例化（先于本方法执行 @FXML 注入），这里统一注入
        // BookContext 与回调。SidebarController 横跨活动栏 / 三个视图 / 底部面板多个
        // FXML 文件，无法归属某个子 FXML，保持手动构造。
        sidebarController = new SidebarController(
                activityGroup,
                tocActivityButton, resourceActivityButton,
                metadataActivityButton, validationActivityButton,
                tocView, resourceView, metadataView,
                bottomPanel, sidePanel, mainSplit);
        sidebarController.setupActivityBarInteraction();

        tocViewController.bind(ctx, this::beginChange, status::set, this::warn);
        tocViewController.wire();
        tocViewController.setOnChapterSelected(this::showChapter);

        sidebarController.setupDefault();
        bindProblemsAccelerator();

        welcomePageController.bind(
                this::onNew,
                draft -> workspaceActivity.openDraft(draft),
                this::onOpenWorkspace,
                this::onExit);
        // 订阅 BookLoadedEvent 自动收起欢迎页（新建 / 打开 / 自动暂存恢复 都触发）
        welcomePageController.subscribeVisibility(ctx);

        bottomPanelController.bind(ctx, validator, editorTabs, contentArea,
                tocViewController, sidebarController,
                this::commitPendingEdits, status::set, status.progressSink());
        bottomPanelController.setupTable();

        metadataViewController.bind(ctx, this::recordBeforeChange, this::markDirty,
                this::refreshAll, this::refreshResources, status::set);

        resourceViewController.bind(ctx, editorTabs, contentArea,
                this::beginChange, this::markDirty,
                this::refreshAll, this::refreshResources,
                () -> metadataViewController.refreshCoverCard(),
                status::refresh, status::set, this::warn,
                this::confirmDiscardChanges, status::showError,
                status.progressSink());

        findBarController.bind(ctx, contentArea,
                this::beginChange, this::markDirty,
                this::reloadEditor, this::refreshPreview,
                status::set, this::confirmDiscardChanges);

        contentArea.textProperty().addListener((obs, oldValue, text) -> {
            if (ctx.loading() || ctx.book() == null) {
                return;
            }
            // 一段连续输入只在第一次击键时记录一次快照（此时 book 还是变更前的状态）
            ensureUndoActivity();
            undoActivity.onTextInput();
            markDirty();
        });

        subscribeAppEvents();

        themeActivity = new ThemeActivity(statusLabel, themeStatusLabel,
                themeLightItem, themeDarkItem, themeSepiaItem,
                status::set, this::refreshPreview);
        themeActivity.initialize();

        wireAutosave();
        wireFileDropWhenSceneReady();

        ensureDocumentActivity();
        setEditorChromeVisible(false);
        workspaceActivity.refreshRecentMenu();
        // 启动恢复扫描：必须在 newBook() 之前判断——否则新建的空书会覆盖 ctx，
        // findRecoverable(ctx) 看到的 currentFile 就是新建后的 null，找不到任何东西。
        promptRecoveryIfAny();
        // 故意不在这里 newBook()：启动后欢迎页是初始视图，用户从欢迎页挑一个动作（新建图书 /
        // 打开图书 / 切换工作空间）才落到 ctx.book() 上，避免一开始就凭空创建一本书造成
        // 「自动暂存里多出一份不会有人认领的临时草稿」的窘境。
    }

    /**
     * 集中订阅 {@link AppEventBus}，把"状态变了 → 调 xxx"的入口
     * 全部从手动回调改为事件订阅。新增子控制器后只需追加订阅，不必改 MainController 主流程。
     */
    private void subscribeAppEvents() {
        AppEventBus bus = ctx.bus();
        // 订阅句柄必须长期持有，不能用 try-with-resources —— 那会在方法返回时自动 close()，
        // 等于「注册完立刻退订」，之后发布的事件全部收不到。
        busSubscribers.add(bus.subscribe(AppEventBus.BookLoadedEvent.class, e -> {
            setEditorChromeVisible(true);
            refreshAll();
        }));
        busSubscribers.add(bus.subscribe(AppEventBus.BookSavedEvent.class, e -> {
            updateTitleAndHistory();
            status.flash("已保存");
        }));
        busSubscribers.add(bus.subscribe(AppEventBus.BookDirtyChangedEvent.class, e -> updateTitleAndHistory()));
    }

    /**
     * 主窗口关闭前的清理入口：统一退订本类与子控制器的总线订阅。
     * 与 {@link WelcomePageController#dispose()} 同一约定，接线点建议放在
     * {@code EpubraApp.start()} 里 stage 的关闭请求处。
     */
    public void dispose() {
        for (Unsubscriber subscriber : busSubscribers) {
            subscriber.close();
        }
        busSubscribers.clear();
        welcomePageController.dispose();
    }

    /**
     * 集中更新标题栏与撤销菜单可用态；保存与脏标记均触发同一组 UI 重画。
     */
    private void updateTitleAndHistory() {
        status.updateTitle();
        status.updateHistoryControls();
    }

    // ------------------------------------------------------------------ 文件

    @FXML
    public void onNew() {
        ensureDocumentActivity();
        documentActivity.onNew();
    }

    @FXML
    public void onOpen() {
        ensureDocumentActivity();
        documentActivity.onOpen();
    }

    @FXML
    public void onOpenWorkspace() {
        workspaceActivity.chooseAndSwitch();
    }

    private void setEditorChromeVisible(boolean visible) {
        FxNodes.setVisibleManaged(activityBar, visible);
        FxNodes.setVisibleManaged(statusBar, visible);
    }

    @FXML
    public void onSave() {
        ensureDocumentActivity();
        documentActivity.onSave();
    }

    @FXML
    public void onSaveAs() {
        ensureDocumentActivity();
        documentActivity.onSaveAs();
    }

    @FXML
    public void onExit() {
        ensureDocumentActivity();
        // 菜单退出走 stage.close()，不依赖标题栏 X 的 onCloseRequest 路径，
        // 确认无未保存更改后、关窗前显式释放订阅资源。dispose() 幂等，
        // 若 stage.close() 也触发了 onCloseRequest，重复调用无副作用。
        documentActivity.onExit(() -> {
            dispose();
            stage.close();
        });
    }

    @FXML
    public void onAbout() {
        ensureDocumentActivity();
        documentActivity.onAbout();
    }

    private void ensureDocumentActivity() {
        if (documentActivity == null) {
            documentActivity = new DocumentActivity(ctx,
                    status::set,
                    this::confirmDiscardChanges,
                    DocumentActivity.defaultDialogs(stage),
                    status.progressSink(),
                    this::reportError);
        }
    }

    /**
     * 错误信息直接打到状态栏。复杂场景会让 DocumentActivity 触发 Alert，这里保持简洁。
     */
    private void reportError(String message) {
        status.set(message);
    }

    // ------------------------------------------------------------------ 撤销 / 重做

    @FXML
    public void onUndo() {
        ensureUndoActivity();
        undoActivity.undo();
    }

    @FXML
    public void onRedo() {
        ensureUndoActivity();
        undoActivity.redo();
    }

    /**
     * 记录一次变更「之前」的状态，并结束当前的输入编辑步。
     *
     * <p>必须在真正修改 {@link Book} 之前调用：它会先把正文与元数据写回，再拍快照，
     * 之后本次操作引起的界面文本变更不再重复计入历史。
     */
    private void beginChange() {
        ensureUndoActivity();
        undoActivity.beginChange();
    }

    /**
     * 记录一次「界面文本即将写回书籍之前」的状态。
     *
     * <p>{@link #beginChange()} 的顺序是先写回、再快照，适用于「先改结构」的操作；
     * 元数据这类「界面文本本身就是变更内容」的操作必须反过来，否则快照里已经是新值。
     */
    private void recordBeforeChange() {
        ensureUndoActivity();
        undoActivity.recordBeforeChange();
    }

    private void commitPendingEdits() {
        ensureUndoActivity();
        undoActivity.commitPendingEdits();
    }

    private void ensureUndoActivity() {
        if (undoActivity == null) {
            undoActivity = new UndoActivity(ctx, status::set, this::clearValidationResults);
            undoActivity.installFlushCallbacks(this::flushCurrentChapter, this::flushMetadata);
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
        status.set("预览已刷新");
    }

    /**
     * 「视图 → 并排预览」开关：标签模式 ↔ 左右并排对照。
     *
     * <p>长文档在标签模式下要反复切 Tab 才能看到改动效果，并排模式让源码与渲染结果同屏，
     * 省掉切换成本。
     */
    @FXML
    public void onToggleSplitPreview() {
        splitPreview = !splitPreview;
        applyPreviewMode();
        refreshPreview();
        status.set(splitPreview ? "已切换为并排预览" : "已切换为标签预览");
    }

    /**
     * 把 contentArea 与 previewView 在两个父容器之间搬移。
     *
     * <p>一个 Node 只能挂在一个父容器下，所以两种模式不能各持一份，只能切一次搬一次。
     * 摘 Tab 内容时先 {@code setContent(null)}——直接把节点塞进 SplitPane 会让 JavaFX
     * 抛「节点已有父容器」异常。
     */
    private void applyPreviewMode() {
        if (splitPreviewPane == null || editorTabs == null || editorTabs.getTabs().size() < 2) {
            return;
        }
        if (splitPreview) {
            editorTabs.getTabs().get(0).setContent(null);
            editorTabs.getTabs().get(1).setContent(null);
            splitPreviewPane.getItems().setAll(contentArea, previewView);
        } else {
            splitPreviewPane.getItems().clear();
            editorTabs.getTabs().get(0).setContent(contentArea);
            editorTabs.getTabs().get(1).setContent(previewView);
        }
        // 两个容器互斥显示：visible 与 managed 必须同步，否则隐藏的那个仍占 StackPane 布局
        FxNodes.setVisibleManaged(editorTabs, !splitPreview);
        FxNodes.setVisibleManaged(splitPreviewPane, splitPreview);
        if (splitPreviewItem != null) {
            splitPreviewItem.setText(splitPreview ? "标签预览" : "并排预览");
        }
    }

    // ------------------------------------------------------------------ 主题
    // 实现全部在 ThemeActivity（activities 包）；FXML 的 onAction 只能绑主控制器方法，
    // 因此这里保留一行委派。

    @FXML
    public void onThemeLight() {
        themeActivity.switchTo(Theme.LIGHT);
    }

    @FXML
    public void onThemeDark() {
        themeActivity.switchTo(Theme.DARK);
    }

    @FXML
    public void onThemeSepia() {
        themeActivity.switchTo(Theme.SEPIA);
    }

    // ------------------------------------------------------------------ 文件拖放

    /**
     * 装配「拖图书文件到窗口即打开」。
     *
     * <p>挂在 Scene 上而不是欢迎页节点上——欢迎页在载入书籍后就隐藏了，挂在那里之后
     * 再也收不到拖放事件；而拖放打开应该是全流程可用的能力，不限于起始页。
     */
    private void wireFileDropWhenSceneReady() {
        Scene scene = statusLabel.getScene();
        if (scene != null) {
            wireFileDropTo(scene);
            return;
        }
        statusLabel.sceneProperty().addListener(new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Scene> obs, Scene oldScene, Scene newScene) {
                if (newScene == null) {
                    return;
                }
                statusLabel.sceneProperty().removeListener(this);
                wireFileDropTo(newScene);
            }
        });
    }

    private void wireFileDropTo(Scene scene) {
        scene.setOnDragOver(event -> {
            if (firstBookFile(event.getDragboard()) != null) {
                event.acceptTransferModes(TransferMode.COPY);
            }
            event.consume();
        });
        scene.setOnDragDropped(event -> {
            Path file = firstBookFile(event.getDragboard());
            if (file == null) {
                event.setDropCompleted(false);
                event.consume();
                return;
            }
            event.setDropCompleted(true);
            event.consume();
            workspaceActivity.openBook(file);
        });
    }

    /** 从拖放载体里挑第一个图书文件；没有则返回 null。 */
    private static Path firstBookFile(Dragboard board) {
        if (board == null || !board.hasFiles()) {
            return null;
        }
        List<File> files = board.getFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            String lower = file.getName().toLowerCase();
            if (file.isFile() && (lower.endsWith(".draft") || lower.endsWith(".epub"))) {
                return file.toPath();
            }
        }
        return null;
    }

     // ------------------------------------------------------------------ 自动暂存

    /**
     * 装配自动暂存的「停顿 N 秒后写盘」节流器。
     *
     * <p>逻辑：
     * <ul>
     *   <li>用户每次改动（{@link #markDirty}）都调 {@code playFromStart()} 重置计时；</li>
     *   <li>计时器到点才调 {@link Autosave#flushNow(BookContext)} 写盘。</li>
     * </ul>
     *
     * <p>若 {@link AutosaveConfig#enabled} 为 false 则完全跳过装配——
     * Preferences 持久化的「自动暂存开关」是用户的最高优先级。
     *
     * <p>状态栏标签走 {@code autosaveStatusLabel}：保存中显示「保存中…」，落盘后回到「自动暂存」。
     * CSS 类切换由 {@code markAutosaveSaving()} / {@code markAutosaveIdle()} 负责。
     */
    private void wireAutosave() {
        if (!ctx.autosaveConfig().enabled()) {
            markAutosaveDisabled();
            return;
        }
        autosaveDebounce = new PauseTransition(
                Duration.seconds(ctx.autosaveConfig().debounceSeconds()));
        autosaveDebounce.setOnFinished(event -> {
            Autosave.flushNow(ctx);
            markAutosaveIdle();
            updateAutosaveLabel();
        });
        markAutosaveIdle();
        updateAutosaveLabel();
    }

    /**
     * 标记为"已禁用"——配置文件说不存就不存，避免给用户错误预期。
     */
    private void markAutosaveDisabled() {
        if (autosaveStatusLabel == null) {
            return;
        }
        autosaveStatusLabel.setText("自动暂存 关");
        autosaveStatusLabel.getStyleClass().removeAll("status-autosave-saving");
        if (!autosaveStatusLabel.getStyleClass().contains("status-autosave-off")) {
            autosaveStatusLabel.getStyleClass().add("status-autosave-off");
        }
    }

    /**
     * 用户刚改了东西——重启节流计时，UI 先翻到"保存中"状态。
     */
    private void markAutosaveSaving() {
        if (autosaveStatusLabel == null) {
            return;
        }
        autosaveStatusLabel.getStyleClass().removeAll("status-autosave-off");
        if (!autosaveStatusLabel.getStyleClass().contains("status-autosave-saving")) {
            autosaveStatusLabel.getStyleClass().add("status-autosave-saving");
        }
    }

    /**
     * 节流到点 → 刚写完盘 → 落回"空闲"样式。
     */
    private void markAutosaveIdle() {
        if (autosaveStatusLabel == null) {
            return;
        }
        autosaveStatusLabel.getStyleClass().removeAll("status-autosave-saving", "status-autosave-off");
    }

    /**
     * 把"自动暂存 开 / 关 + 间隔 N 秒"展示到状态栏标签上。
     */
    private void updateAutosaveLabel() {
        if (autosaveStatusLabel == null) {
            return;
        }
        if (!ctx.autosaveConfig().enabled()) {
            markAutosaveDisabled();
            return;
        }
        autosaveStatusLabel.setText("自动暂存 " + ctx.autosaveConfig().debounceSeconds() + "s");
    }

    /**
     * 启动时扫描可恢复的草稿：发现就弹 Alert，让用户选恢复还是丢弃。
     *
     * <p>必须放在 {@code newBook()} 之前调用——{@code newBook} 会重置 ctx.book() 和
     * {@code ctx.currentFile()}，{@link Autosave#findRecoverable} 会因此看不到旧文件的草稿。
     */
    private void promptRecoveryIfAny() {
        Optional<Path> draft = Autosave.findRecoverable(ctx);
        if (draft.isEmpty()) {
            return;
        }
        Path file = draft.get();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("发现未保存的草稿");
        alert.setHeaderText("检测到上次未保存的修改");
        alert.setContentText("文件：" + file.getFileName() + "\n是否恢复该草稿？");
        if (stage != null) {
            alert.initOwner(stage);
        }
        ButtonType restoreBtn = new ButtonType("恢复草稿");
        ButtonType discardBtn = new ButtonType("丢弃");
        alert.getButtonTypes().setAll(restoreBtn, discardBtn);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == discardBtn) {
            // 丢弃：删除草稿文件，让后续 newBook() 拿干净的初始状态
            try {
                java.nio.file.Files.deleteIfExists(file);
            } catch (IOException e) {
                System.getLogger(MainController.class.getName())
                        .log(System.Logger.Level.WARNING,
                                "Failed to discard draft: " + e.getMessage(), e);
            }
            return;
        }
        // 恢复：把草稿读回 ctx；标记 dirty 让用户感知到内容已恢复但未保存。
        try {
            Book restored = Autosave.readDraft(file);
            ctx.setBook(restored);
            // 草稿名若是 "untitled.draft" → 没有对应的主文件路径；否则从草稿路径推断。
            String draftName = file.getFileName().toString();
            if (!Autosave.UNTITLED_DRAFT_NAME.equals(draftName)) {
                // 草稿文件名约定：<main-stem>.draft → 主文件 = <main-stem>.epub
                String stem = draftName.substring(0, draftName.length() - Autosave.DRAFT_SUFFIX.length());
                Path inferredMain = file.getParent().resolve(stem + ".epub");
                if (java.nio.file.Files.exists(inferredMain)) {
                    ctx.setCurrentFile(inferredMain);
                    ctx.book().setSource(inferredMain);
                } else {
                    ctx.setCurrentFile(null);
                }
            } else {
                ctx.setCurrentFile(null);
            }
            setCurrentChapter(null);
            ctx.setDirty(true);
            ctx.history().reset();
            ctx.setEditCaptured(false);
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
            status.set("已从草稿恢复：" + file.getFileName());
        } catch (IOException e) {
            warn("草稿恢复失败：" + e.getMessage());
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

    /**
     * 底部面板头上的关闭按钮。
     */
    @FXML
    public void onHideProblems() {
        sidebarController.hideProblems();
    }

    // 校验全部委托给 ValidationController：MainController 只保留入口与回调钩子。

    /**
     * 清空校验结果——{@link UndoActivity} 在撤销/重做、打开、新建时需要回调它。
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
    // refreshAll / UndoActivity 的 flush 回调里调它的 loadIntoFields / flush。

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

    // 以下编辑工具条入口的实现都在 InsertActivity（activities 包）；
    // FXML 的 onAction 只能绑主控制器方法，故保留一行委派。

    @FXML
    public void onInsertParagraph() {
        insertActivity.paragraph();
    }

    @FXML
    public void onInsertHeading() {
        insertActivity.heading();
    }

    @FXML
    public void onInsertBold() {
        insertActivity.bold();
    }

    @FXML
    public void onInsertItalic() {
        insertActivity.italic();
    }

    @FXML
    public void onInsertList() {
        insertActivity.list();
    }

    // ------------------------------------------------------------------
    // 编辑工具条（段落 / 标题 / 加粗 / 斜体 / 列表）

    @FXML
    public void onCleanupResources() {
        resourceViewController.cleanupUnused();
    }

    /**
     * 刷新资源列表；nav 与 ncx 由写出流程自动维护，不展示给用户。委托 ResourceController。
     */
    private void refreshResources() {
        resourceViewController.refresh();
    }

    // 目录树交互已迁出到 TocController，MainController 仅保留 showChapter 用于「章节被选中」回调。

    private void refreshAll() {
        if (ctx.book() == null) {
            return;
        }
        ctx.invalidateWordCounts();
        ctx.setLoading(true);
        try {
            metadataViewController.loadIntoFields(ctx.book().metadata());
        } finally {
            ctx.setLoading(false);
        }
        refreshToc();
        refreshResources();
        status.refresh();
    }

    /**
     * 目录树刷新转发；null 防护保留——bind 之前不会有刷新请求，但保持防御式。
     */
    private void refreshToc() {
        if (tocViewController != null) {
            tocViewController.refresh();
        }
    }

    private void showChapter(ChapterNode node) {
        if (ctx.book() == null) {
            return;
        }
        flushCurrentChapter();
        setCurrentChapter(node);
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
        status.refresh();
    }

    /**
     * 把当前章节资源的内容重新读回编辑器；用于内容被程序化修改后同步界面。
     */
    private void reloadEditor() {
        ChapterNode current = currentChapter();
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
        ChapterNode current = currentChapter();
        if (current == null || current.resource() == null) {
            previewView.getEngine().loadContent(PreviewHtml.emptyDocument(themeActivity.current()));
            return;
        }
        // 预览区是 WebView，吃不到 -epubra-* 变量，改为往 XHTML 里注入一段内联主题样式
        previewView.getEngine().loadContent(
                PreviewHtml.withTheme(current.resource().asString(), themeActivity.current()),
                "application/xhtml+xml");
    }

    /**
     * 把编辑器中的内容写回当前章节资源。
     */
    private void flushCurrentChapter() {
        ChapterNode current = currentChapter();
        if (current == null || current.resource() == null) {
            return;
        }
        current.resource().setString(contentArea.getText());
        ctx.invalidateWordCounts();
    }

    /**
     * 撤销快照回放前把元数据面板的当前值写回书籍；实现已迁 MetadataViewController。
     */
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
        alert.initOwner(stage);
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }

    private void warn(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("提示");
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.initOwner(stage);
        alert.showAndWait();
    }

    private void markDirty() {
        ctx.setDirty(true);
        // 内容 / 元数据改动都触发自动暂存节流；loading 期间的内容回填不算真实改动，跳过。
        if (!ctx.loading() && autosaveDebounce != null) {
            autosaveDebounce.playFromStart();
            markAutosaveSaving();
        }
        status.refresh();
    }

}
