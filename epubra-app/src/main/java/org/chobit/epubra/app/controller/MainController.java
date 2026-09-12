package org.chobit.epubra.app.controller;

import netscape.javascript.JSObject;
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
import org.chobit.epubra.app.ui.ToolbarIcons;
import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.document.Autosave;
import org.chobit.epubra.app.document.AutosaveConfig;
import org.chobit.epubra.app.editor.PreviewHtml;
import org.chobit.epubra.app.editor.PreviewMirror;
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
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
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
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.DirectoryChooser;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    /** 可视化编辑视图：contenteditable 的 XHTML，改动经 JS 桥回写正文。 */
    @FXML
    private WebView visualEditorView;
    /** 格式化工具条：只挂在「编辑」tab 内，作用于 {@link #visualEditorView}。 */
    @FXML
    private FlowPane editorToolbar;
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

    /**
     * 主窗口 stage 由 {@code EpubraApp} 在 FXML 加载完成后注入——晚于 {@link #initialize()}。
     *
     * <p>因此两件事必须在这里补做，不能放在 {@code initialize()}：
     * <ol>
     *   <li>把 stage 补发给需要它的子控制器（FileChooser 的 owner）；</li>
     *   <li>启动草稿恢复提示 {@link #promptRecoveryIfAny()}——它要弹窗，需要一个既有的
     *       owner 窗口；更重要的是 GUI 测试只 load FXML + {@code stage.show()}，
     *       不会调本方法，弹窗因此不可能在无头/无人应答的环境里挂住 FX 线程。</li>
     * </ol>
     */
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
        promptRecoveryIfAny();
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
        // 编辑视图与预览视图共用同一个 WebView 缓存目录，避免多套 native 缓存
        WebEngine visualEngine = visualEditorView.getEngine();
        visualEngine.setUserDataDirectory(AppPaths.webviewCacheDir().toFile());
        // window 在每次文档加载后都是新对象，桥必须跟着重装，否则 loadContent 之后
        // 旧 window 上的 epubraBridge 就没了，页面里的改动再也回不来。
        visualEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                installVisualEditorBridge();
            }
        });

        // 资源镜像：预览 / 可视化编辑器里的相对引用（图片、字体、CSS）要靠它才有解析基准。
        // 惰性同步，构造本身不碰磁盘；换书时自动清空重建。
        previewMirror = PreviewMirror.forUserData();

        // 工具条文字换图标：图形 + Tooltip 由 ToolbarIcons 统一管理（见该类 javadoc）。
        ToolbarIcons.install(editorToolbar);

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

        resourceViewController.bind(ctx,
                this::beginChange, this::markDirty,
                this::refreshAll, this::refreshResources,
                () -> metadataViewController.refreshCoverCard(),
                status::refresh, status::set, this::warn,
                this::confirmDiscardChanges, status::showError,
                status.progressSink(), this::insertXhtmlIntoActiveEditor);

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

        wireEditorTabSwitching();
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
        // 故意不在这里 newBook()：启动后欢迎页是初始视图，用户从欢迎页挑一个动作（新建图书 /
        // 打开图书 / 切换工作空间）才落到 ctx.book() 上，避免一开始就凭空创建一本书造成
        // 「自动暂存里多出一份不会有人认领的临时草稿」的窘境。
        //
        // 启动草稿恢复提示**不在这里**，放在 setStage() 末尾——见该方法注释。
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
        // 撤销 / 重做把整个 Book 换成了新实例，界面上的旧节点全部失效：必须重新灌一遍。
        // 没有这个订阅时，撤销只改模型不改界面——用户看不到任何变化，而且下一次
        // flushCurrentChapter 会把界面上的旧文本写回去，等于把撤销悄悄抹掉。
        // refreshAll() 内部经 refreshToc() → 章节选中回调 → showChapter() 重载编辑区，
        // 因此这里不需要再手工刷新编辑器。
        busSubscribers.add(bus.subscribe(AppEventBus.BookRestoredEvent.class, e -> {
            refreshAll();
            updateTitleAndHistory();
        }));
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
        if (splitPreviewPane == null || editorTabs == null || editorTabs.getTabs().size() < 3) {
            return;
        }
        // tab 顺序：0 编辑 / 1 源码 / 2 预览；并排模式仍只搬源码与预览两个节点，
        // 编辑 tab 留在标签页里（可视化编辑与并排预览互斥使用）。
        if (splitPreview) {
            editorTabs.getTabs().get(1).setContent(null);
            editorTabs.getTabs().get(2).setContent(null);
            splitPreviewPane.getItems().setAll(contentArea, previewView);
        } else {
            splitPreviewPane.getItems().clear();
            editorTabs.getTabs().get(1).setContent(contentArea);
            editorTabs.getTabs().get(2).setContent(previewView);
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
     * 启动时扫描可恢复的草稿：发现就弹 Alert，让用户选恢复到哪个工作空间。
     *
     * <p>由 {@link #setStage(Stage)} 调用（而不是 {@code initialize()}）：需要在 stage 就绪后
     * 才有 owner 窗口，且 GUI 测试链路上不会触发弹窗。
     *
     * <h2>为什么恢复必须绑定工作空间</h2>
     * <p>没有工作空间就无从创建 / 维护图书，反过来"有图书草稿就该有工作空间"。启动扫描
     * 命中的都是<b>未归属工作空间</b>的孤儿草稿（{@code ~/.Epubra/autosave/*.draft}），
     * 所以这里的恢复语义是<b>收编</b>：读出来 → 以书名写成
     * {@code <workspace>/<书名>.draft} → 清掉孤儿。提示里必须写明工作空间的名称与完整路径，
     * 恢复后 {@code ctx.currentFile()} 指向工作空间里的真实文件（旧实现留 null，
     * 造成"恢复出来的书不属于任何工作空间，改完又写回孤儿目录"的死循环）。
     *
     * <h2>为什么用 {@code show()} 而不是 {@code showAndWait()}</h2>
     * <p>启动流程跑在 FX 线程上。{@code showAndWait()} 会开一个嵌套事件循环把 FX 线程
     * 停在原地，一旦没人应答弹窗（无人值守启动、GUI 测试里恰好存在遗留的孤儿草稿），
     * 整个应用连同测试一起挂死。这里改为 {@code show()} + {@code setOnHidden}：
     * 弹窗依旧是模态的，用户应答后走同一条回调，但 FX 线程立即返回。
     */
    private void promptRecoveryIfAny() {
        Optional<Path> orphan = Autosave.findRecoverable(ctx);
        if (orphan.isEmpty()) {
            return;
        }
        Path file = orphan.get();
        Path workspace = WorkspaceStore.initial().orElse(null);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("恢复草稿");
        alert.setHeaderText(Autosave.recoveryPromptHeader(workspace));
        alert.setContentText(Autosave.recoveryPromptText(file, workspace, Instant.now()));
        if (stage != null) {
            alert.initOwner(stage);
        }
        ButtonType acceptBtn = new ButtonType(
                workspace == null ? "选择工作空间并恢复" : "恢复到此工作空间");
        ButtonType discardBtn = new ButtonType("丢弃");
        alert.getButtonTypes().setAll(acceptBtn, discardBtn);
        alert.setOnHidden(event -> onRecoveryChoice(file, workspace, alert.getResult(), acceptBtn));
        alert.show();
    }

    /** 恢复弹窗的应答处理：接受 → 收编进工作空间；其它（含"丢弃"/直接关窗）→ 删掉孤儿。 */
    private void onRecoveryChoice(Path file, Path workspace, ButtonType result, ButtonType acceptBtn) {
        if (result != acceptBtn) {
            // 丢弃：删掉孤儿草稿，让后续启动不再反复提示。
            deleteQuietly(file);
            return;
        }
        Path targetWorkspace = workspace != null ? workspace : chooseRecoveryWorkspace();
        if (targetWorkspace == null) {
            // 没有工作空间就无从归属——保留草稿，下次启动再提示。
            status.set("未选择工作空间，草稿仍保留在自动暂存目录");
            return;
        }
        adoptOrphanDraft(file, targetWorkspace);
    }

    /** 让用户为孤儿草稿挑一个工作空间；取消时返回 null。 */
    private Path chooseRecoveryWorkspace() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择草稿要恢复到的工作空间");
        workspacePathHint().ifPresent(dir -> chooser.setInitialDirectory(dir.toFile()));
        File selected = chooser.showDialog(stage);
        return selected == null ? null : selected.toPath();
    }

    private Optional<Path> workspacePathHint() {
        return WorkspaceStore.initial().filter(Files::isDirectory);
    }

    /**
     * 把孤儿草稿收编进工作空间：读内容 → 以书名写成 {@code <ws>/<书名>.draft} → 删孤儿
     * → 落到 ctx（{@code currentFile} 指向工作空间内的文件）→ 切首页工作空间 → 广播加载事件。
     */
    private void adoptOrphanDraft(Path orphan, Path workspace) {
        try {
            Book restored = Autosave.readDraft(orphan);
            String title = restored.metadata().firstTitle();
            String stem = title == null || title.isBlank()
                    ? Autosave.stripDraftSuffix(orphan.getFileName().toString())
                    : title;
            Path target = Autosave.writeIntoWorkspace(restored, workspace, stem);
            deleteQuietly(orphan);

            WorkspaceStore.add(workspace);
            ctx.setBook(restored);
            ctx.setCurrentFile(target);
            restored.setSource(target);
            setCurrentChapter(null);
            ctx.setDirty(true);
            ctx.history().reset();
            ctx.setEditCaptured(false);
            if (!workspace.equals(welcomePageController.currentWorkspace())) {
                welcomePageController.showWorkspace(workspace);
            }
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
            status.set("已把草稿恢复到工作空间：" + target.getFileName());
        } catch (IOException e) {
            warn("草稿恢复失败：" + e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.getLogger(MainController.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "Failed to discard draft: " + e.getMessage(), e);
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

    /**
     * 编辑 tab 工具条「图片」按钮：从本机选图（可多选）→ 导入为书内资源 → 插入正文。
     *
     * <p>与 {@link #onInsertImage()}（插入资源列表里选中的图）是两条不同入口：
     * 后者继续服务菜单栏与资源面板，本入口只服务编辑器工具条。
     */
    @FXML
    public void onInsertImageFromDisk() {
        resourceViewController.insertImagesFromDisk();
    }

    // 工具条入口（段落 / 标题 / 加粗 / 斜体 / 列表）挂在「编辑」tab 的 WebView 上，
    // 优先作用于可视化编辑器；编辑器还没就绪时退回源码区的片段插入（InsertActivity），
    // 避免点击被吞掉。FXML 的 onAction 只能绑主控制器方法，故实现留在这里。

    @FXML
    public void onInsertParagraph() {
        if (!applyVisualFormat("paragraph")) {
            insertActivity.paragraph();
        }
    }

    @FXML
    public void onInsertHeading() {
        if (!applyVisualFormat("heading")) {
            insertActivity.heading();
        }
    }

    @FXML
    public void onInsertBold() {
        if (!applyVisualFormat("bold")) {
            insertActivity.bold();
        }
    }

    @FXML
    public void onInsertItalic() {
        if (!applyVisualFormat("italic")) {
            insertActivity.italic();
        }
    }

    @FXML
    public void onInsertList() {
        if (!applyVisualFormat("list")) {
            insertActivity.list();
        }
    }

    @FXML
    public void onInsertQuote() {
        applyVisualFormat("quote");
    }

    @FXML
    public void onInsertRule() {
        applyVisualFormat("rule");
    }

    @FXML
    public void onInsertUnderline() {
        applyVisualFormat("underline");
    }

    @FXML
    public void onInsertStrike() {
        applyVisualFormat("strike");
    }

    @FXML
    public void onInsertCode() {
        applyVisualFormat("code");
    }

    /** 链接：先问一句网址，再交给可视化编辑器把选区（或空选区）包成 {@code <a href>}。 */
    @FXML
    public void onInsertLink() {
        if (!visualEditorReady()) {
            status.set("编辑视图尚未就绪，请稍后重试");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("插入链接");
        dialog.setHeaderText(null);
        dialog.setContentText("链接地址：");
        if (stage != null) {
            dialog.initOwner(stage);
        }
        Optional<String> input = dialog.showAndWait();
        if (input.isEmpty()) {
            return;
        }
        String href = input.get().trim();
        if (href.isEmpty()) {
            status.set("链接地址为空，已取消");
            return;
        }
        if (!applyVisualFormat("link", href)) {
            status.set("链接地址无法使用（不支持 javascript: / data: 这类地址）");
        }
    }

    // ------------------------------------------------------------------
    // 资源维护（清理未引用资源 / 刷新资源列表）

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
        // 编辑视图不会在非「编辑」tab 时自动重载，先标记失效，防止旧章节内容被回写到新章节
        visualEditorLoaded = false;
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
        if (onVisualTab()) {
            reloadVisualEditor();
        }
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
        // 正文被程序化改写（撤销 / 元数据应用等），可视化编辑器里的副本已过期
        if (onVisualTab()) {
            reloadVisualEditor();
        } else {
            visualEditorLoaded = false;
        }
        ctx.setLoading(true);
        try {
            contentArea.setText(current.resource().asString());
            contentArea.positionCaret(0);
        } finally {
            ctx.setLoading(false);
        }
    }

    // ------------------------------------------------------------------ 可视化编辑

    /**
     * 安装 JS 桥：编辑视图里的改动经 {@code window.epubraBridge.onEdited(xhtml)} 回传。
     *
     * <p>回调在 FX 线程触发（WebView 的 JS 引擎就在 FX 线程上跑），可直接改 {@code Book}。
     */
    private void installVisualEditorBridge() {
        JSObject window = (JSObject) visualEditorView.getEngine().executeScript("window");
        window.setMember("epubraBridge", new VisualEditBridge());
    }

    /** 暴露给页面脚本的回写入口；必须是 public 类 + public 方法，桥才能反射调用。 */
    public final class VisualEditBridge {
        public void onEdited(String xhtml) {
            if (xhtml == null || xhtml.isBlank()) {
                return;
            }
            ChapterNode current = currentChapter();
            if (current == null || current.resource() == null) {
                return;
            }
            String existing = current.resource().asString();
            if (existing != null && existing.equals(xhtml)) {
                return; // 内容没变（例如只是切了焦点）——不打扰撤销栈
            }
            // 与源码编辑同样走 onTextInput：一次连续输入只记一次快照，600ms 静默合并。
            // 不能用 beginChange()——它会 commitPendingEdits() → flushCurrentChapter()
            // → flushVisualEditor() → 回到这里，构成递归。
            ensureUndoActivity();
            undoActivity.onTextInput();
            current.resource().setString(xhtml);
            ctx.invalidateWordCounts();
            markDirty();
            syncSourceFromVisualEditor(xhtml);
            status.refresh();
        }

        /**
         * 光标 / 选区变化：把当前生效的格式名传给 Java，点亮工具条。
         *
         * <p>页面在 {@code selectionchange} / {@code keyup} / {@code mouseup} 时主动上报，
         * 不需要 Java 轮询。
         */
        public void onSelectionChanged(String activeFormats) {
            updateToolbarState(activeFormats);
        }

        /**
         * Ctrl+Z / Ctrl+Y：走应用级快照撤销，与菜单、源码区共用一本账。
         *
         * <p>必须挪到下一个脉冲执行——此刻还在 JS 调用栈里，而撤销会重载整个编辑视图
         * （{@code loadContent}），在 {@code executeScript} 中途换页面是不安全的。
         */
        public void onUndo() {
            Platform.runLater(() -> {
                ensureUndoActivity();
                undoActivity.undo();
            });
        }

        /** Ctrl+Y / Ctrl+Shift+Z：同 {@link #onUndo()}，只是反转方向。 */
        public void onRedo() {
            Platform.runLater(() -> {
                ensureUndoActivity();
                undoActivity.redo();
            });
        }
    }

    /** 把可视化编辑的结果同步到源码 tab，避免两个 tab 显示的内容不一致。 */
    private void syncSourceFromVisualEditor(String xhtml) {
        if (contentArea == null || contentArea.isDisabled()) {
            return;
        }
        // 用户正停在源码 tab 打字时不要覆盖他的输入——以他为权威，等他改完自然写回正文
        if (contentArea.isFocused()) {
            return;
        }
        ctx.setLoading(true);
        try {
            int caret = contentArea.getCaretPosition();
            contentArea.setText(xhtml);
            contentArea.positionCaret(Math.min(caret, xhtml.length()));
        } finally {
            ctx.setLoading(false);
        }
    }

    /**
     * 把当前章节载入可视化编辑器。
     *
     * <p>只在切到「编辑」tab 或换章节时调用——不为每次击键重建文档，否则输入会被打断。
     */
    private void reloadVisualEditor() {
        ChapterNode current = currentChapter();
        String xhtml = current == null || current.resource() == null
                ? ""
                : current.resource().asString();
        visualEditorLoaded = current != null && current.resource() != null;
        visualEditorView.getEngine().loadContent(
                PreviewHtml.editableDocument(xhtml, themeActivity.current(), previewBaseHref(current)),
                "application/xhtml+xml");
    }

    /**
     * 主动把编辑器里的最新内容拉回正文（不等 600ms 节流）。
     *
     * <p>切章节 / 保存前调用：用户可能刚敲完就点了别处，节流还没到点。
     *
     * @return 是否确实写回了内容；调用方据此避免再用旧的源码文本覆盖
     */
    private boolean flushVisualEditor() {
        if (visualEditorView == null || !visualEditorLoaded) {
            return false;
        }
        try {
            Object result = visualEditorView.getEngine().executeScript("window.epubraSerialize()");
            if (result instanceof String xhtml && !xhtml.isBlank()) {
                ChapterNode current = currentChapter();
                if (current != null && current.resource() != null
                        && !xhtml.equals(current.resource().asString())) {
                    // 同 onEdited：复用输入编辑步的合并逻辑，且不能调 beginChange()（递归）
                    ensureUndoActivity();
                    undoActivity.onTextInput();
                    current.resource().setString(xhtml);
                    ctx.invalidateWordCounts();
                    markDirty();
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            // 页面尚未加载完 / 脚本不可用：忽略，正文保持原样
        }
        return false;
    }

    /**
     * 编辑 tab 切换联动：切到「编辑」时把当前章节推给可视化编辑器，
     * 切到「预览」时刷新渲染（编辑期间不重载页面，免得打断输入）。
     */
    private void wireEditorTabSwitching() {
        if (editorTabs == null) {
            return;
        }
        editorTabs.getSelectionModel().selectedIndexProperty().addListener((obs, oldIdx, newIdx) -> {
            int index = newIdx == null ? -1 : newIdx.intValue();
            // 离开「编辑」tab：先把最新内容推回正文，再让它失效——不能依赖页面 blur 一定触发
            if (oldIdx != null && oldIdx.intValue() == VISUAL_TAB_INDEX && index != VISUAL_TAB_INDEX) {
                flushVisualEditor();
                visualEditorLoaded = false;
                clearToolbarState();
            }
            if (index < 0 || currentChapter() == null) {
                return;
            }
            if (index == VISUAL_TAB_INDEX) {
                // 新文档是全新的一棵树，旧的高亮不再成立
                clearToolbarState();
                reloadVisualEditor();
            } else if (index == PREVIEW_TAB_INDEX) {
                refreshPreview();
            }
        });
    }

    /** 当前是否停在「编辑」tab。 */
    private boolean onVisualTab() {
        return editorTabs != null && editorTabs.getSelectionModel().getSelectedIndex() == VISUAL_TAB_INDEX;
    }

    /** tab 索引：与 main-window.fxml 里的顺序一一对应。 */
    private static final int VISUAL_TAB_INDEX = 0;
    private static final int SOURCE_TAB_INDEX = 1;
    private static final int PREVIEW_TAB_INDEX = 2;

    // ------------------------------------------------------------------ 工具条对可视化编辑器的操作

    /**
     * 对可视化编辑器施加一次富文本操作。
     *
     * <p>kind 取值：{@code paragraph} / {@code heading} / {@code quote} / {@code list} /
     * {@code rule} / {@code bold} / {@code italic} / {@code underline} / {@code strike} /
     * {@code code} / {@code link}，全部是硬编码的 ASCII 字面量，可直接拼进脚本，无需转义。
     *
     * @return 是否真的改了内容；false 表示编辑视图还没就绪或命令被拒绝
     */
    private boolean applyVisualFormat(String kind) {
        return applyVisualFormat(kind, null);
    }

    /**
     * 带参数的格式化命令（目前只有 {@code link} 需要 href）。
     *
     * <p>参数经 {@code window} 上的临时成员传入，不拼进脚本——URL 里可能有引号与反斜杠。
     */
    private boolean applyVisualFormat(String kind, String value) {
        if (!visualEditorReady()) {
            return false;
        }
        WebEngine engine = visualEditorView.getEngine();
        try {
            if (value == null) {
                Object ok = engine.executeScript("window.epubraFormat('" + kind + "')");
                return Boolean.TRUE.equals(ok);
            }
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember(PENDING_VALUE_MEMBER, value);
            Object ok = engine.executeScript(
                    "window.epubraFormat('" + kind + "', window." + PENDING_VALUE_MEMBER + ")");
            window.setMember(PENDING_VALUE_MEMBER, null);
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException notLoadedYet) {
            return false;
        }
    }

    /** 可视化编辑器是否可用于施加上下文命令（已加载完成且内容对应当前章节）。 */
    private boolean visualEditorReady() {
        return visualEditorView != null && visualEditorLoaded;
    }

    /**
     * 把一段 XHTML 片段插到可视化编辑器的光标处（图片等）。
     *
     * <p>片段先经 {@code window} 上的临时成员传进去，而不是拼进脚本字符串——
     * 标签里带引号，手工转义容易出错。
     */
    private boolean insertHtmlIntoVisualEditor(String xhtml) {
        if (!visualEditorReady() || xhtml == null) {
            return false;
        }
        WebEngine engine = visualEditorView.getEngine();
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember(PENDING_HTML_MEMBER, xhtml);
            Object ok = engine.executeScript(
                    "window.epubraInsertHtml(window." + PENDING_HTML_MEMBER + ")");
            window.setMember(PENDING_HTML_MEMBER, null);
            return Boolean.TRUE.equals(ok);
        } catch (RuntimeException notLoadedYet) {
            return false;
        }
    }

    /** {@link #applyVisualFormat(String, String)} 传参用的临时成员名。 */
    private static final String PENDING_VALUE_MEMBER = "__epubraPendingValue";

    /** 工具条按钮「当前格式生效」时挂的样式类，见 app.css 的 {@code .flat-button.active}。 */
    private static final String TOOLBAR_ACTIVE_CLASS = "active";

    /** 动作按钮的标记样式类（如「图片」）：没有「光标处格式生效」状态，不参与点亮。 */
    private static final String TOOLBAR_ACTION_CLASS = "toolbar-action";

    /**
     * 按 {@code window.epubraQuery()} 的返回值点亮工具条。
     *
     * <p>按钮的 {@code id} 就是格式名（见 main-window.fxml），所以这里不用为每个按钮
     * 维护一个字段——遍历子节点读 id 即可，加按钮时不必改 Java。
     *
     * @param active 空格分隔的生效格式名，如 {@code "bold italic"}；空串表示无
     */
    private void updateToolbarState(String active) {
        if (editorToolbar == null) {
            return;
        }
        Set<String> on = active == null || active.isBlank()
                ? Set.of()
                : new HashSet<>(List.of(active.trim().split("\\s+")));
        for (Node child : editorToolbar.getChildren()) {
            if (!(child instanceof Button button) || button.getId() == null) {
                continue;
            }
            // 动作按钮（如「图片」= 弹文件选择器）没有「生效格式」状态，
            // epubraQuery 永远不会返回它的 id；不跳过的话它只是一个永不点亮的摆设。
            if (button.getStyleClass().contains(TOOLBAR_ACTION_CLASS)) {
                continue;
            }
            boolean shouldBeOn = on.contains(button.getId());
            boolean isOn = button.getStyleClass().contains(TOOLBAR_ACTIVE_CLASS);
            if (shouldBeOn && !isOn) {
                button.getStyleClass().add(TOOLBAR_ACTIVE_CLASS);
            } else if (!shouldBeOn && isOn) {
                button.getStyleClass().remove(TOOLBAR_ACTIVE_CLASS);
            }
        }
    }

    /** 离开编辑 tab 时清掉工具条的高亮，避免切回来还留着上一章的状态。 */
    private void clearToolbarState() {
        updateToolbarState("");
    }

    /** {@link #insertHtmlIntoVisualEditor} 传片段用的临时成员名。 */
    private static final String PENDING_HTML_MEMBER = "__epubraPendingHtml";

    /**
     * 把一段 XHTML 片段插到「当前激活的编辑器」。
     *
     * <p>编辑 tab → 插入可视化编辑器的光标处，不切 tab；否则先切到源码 tab（离开编辑 tab
     * 的联动会先把可视化编辑器里未同步的改动落盘），再插到源码区的光标处。
     *
     * <p>返回值供调用方（{@code ResourceController}）判断是否真的插进去了：可视化编辑器
     * 可能尚未加载完成、源码区可能被禁用，这些情况下会返回 {@code false}，调用方不得
     * 宣告「已插入」。
     */
    private boolean insertXhtmlIntoActiveEditor(String xhtml) {
        if (xhtml == null || xhtml.isEmpty()) {
            return false;
        }
        if (onVisualTab() && insertHtmlIntoVisualEditor(xhtml)) {
            return true;
        }
        if (editorTabs != null) {
            editorTabs.getSelectionModel().select(SOURCE_TAB_INDEX);
        }
        if (contentArea == null || contentArea.isDisabled()) {
            return false;
        }
        int at = contentArea.getAnchor();
        contentArea.insertText(at, xhtml);
        contentArea.positionCaret(at + xhtml.length());
        return true;
    }

    /**
     * 编辑视图当前内容是否<b>对应当前章节</b>。
     *
     * <p>必须严格维护：编辑视图在非「编辑」tab 时不会随章节切换重载，若此时误判为「已加载」
     * 并拉取内容，会把上一章正文写到新章节里。
     */
    private boolean visualEditorLoaded;

    /**
     * 预览 / 可视化编辑器的资源镜像（{@code ~/.Epubra/preview/}）。
     *
     * <p>存在意义：{@code loadContent} 的页面源是 {@code about:blank}，不注入 {@code <base>}
     * 的话正文里的相对图片引用一个也加载不出来。
     */
    private PreviewMirror previewMirror;

    private void refreshPreview() {
        ChapterNode current = currentChapter();
        if (current == null || current.resource() == null) {
            previewView.getEngine().loadContent(PreviewHtml.emptyDocument(themeActivity.current()));
            return;
        }
        // 预览区是 WebView，吃不到 -epubra-* 变量，改为往 XHTML 里注入一段内联主题样式；
        // 相对引用（图片等）则靠 <base> 指向资源镜像才解析得出来。
        previewView.getEngine().loadContent(
                PreviewHtml.withBaseHref(
                        PreviewHtml.withTheme(current.resource().asString(), themeActivity.current()),
                        previewBaseHref(current)),
                "application/xhtml+xml");
    }

    /**
     * 预览 / 可视化编辑器里相对引用的解析基准：当前章节在资源镜像里的目录 URI。
     *
     * <p>WebView 走 {@code loadContent}，页面源是 {@code about:blank}，没有基准时正文里的
     * {@code <img src="../images/a.png"/>} 会静默加载失败。返回 {@code null} 表示镜像不可用，
     * 此时退回「不注入 base」的老行为——图片显示不出来，但正文一切照常。
     */
    private String previewBaseHref(ChapterNode current) {
        if (previewMirror == null || current == null || current.resource() == null) {
            return null;
        }
        return previewMirror.baseHrefFor(ctx.book(), current.resource().href());
    }

    /**
     * 把编辑器中的内容写回当前章节资源。
     */
    private void flushCurrentChapter() {
        // 可视化编辑器里有尚未同步的改动：它已写回正文并同步了源码区，
        // 此时再用源码区的文本覆盖会把刚敲的内容冲掉。
        if (flushVisualEditor()) {
            return;
        }
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
