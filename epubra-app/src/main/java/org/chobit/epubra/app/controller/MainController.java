package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.EpubraApp;
import org.chobit.epubra.app.activities.AutosaveIndicator;
import org.chobit.epubra.app.activities.DocumentActivity;
import org.chobit.epubra.app.activities.DraftRecoveryActivity;
import org.chobit.epubra.app.activities.EditorShellActivity;
import org.chobit.epubra.app.activities.FileDropActivity;
import org.chobit.epubra.app.activities.InsertActivity;
import org.chobit.epubra.app.activities.StatusCoordinator;
import org.chobit.epubra.app.activities.ThemeActivity;
import org.chobit.epubra.app.activities.UndoActivity;
import org.chobit.epubra.app.activities.WorkspaceActivity;
import org.chobit.epubra.app.controller.layout.SidebarController;
import org.chobit.epubra.app.controller.view.FindController;
import org.chobit.epubra.app.controller.view.MetadataViewController;
import org.chobit.epubra.app.controller.view.PreviewController;
import org.chobit.epubra.app.controller.view.ResourceController;
import org.chobit.epubra.app.controller.view.TocController;
import org.chobit.epubra.app.controller.view.ValidationController;
import org.chobit.epubra.app.controller.view.WelcomePageController;
import org.chobit.epubra.app.context.Unsubscriber;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.app.ui.ToolbarIcons;
import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.editor.EditorStyleControls;
import org.chobit.epubra.app.editor.EditorToolbarController;
import org.chobit.epubra.app.editor.TextSearch;
import org.chobit.epubra.app.editor.Theme;
import org.chobit.epubra.app.editor.VisualEditorSession;
import org.chobit.epubra.app.platform.AppPaths;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;
import org.chobit.epubra.lib.validation.EpubValidator;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.IndexRange;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.geometry.Insets;
import javafx.scene.layout.VBox;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;

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
    /** 可视化编辑视图：contenteditable 的 XHTML，改动经 JS 桥回写正文。 */
    @FXML
    private WebView visualEditorView;
    /** 格式化工具条：只挂在「编辑」tab 内，作用于 {@link #visualEditorView}。 */
    @FXML
    private FlowPane editorToolbar;
    /**
     * 工具条上的样式控件：字体 / 文字颜色 / 对齐。
     *
     * <p>候选项与命令在 {@link EditorStyleControls} 里装配——FXML 的 {@code onAction}
     * 只能绑无参方法，而这三个控件下发的命令都要带值，所以在 Java 侧接线。
     * 字号不在此列：它由 {@code size-up} / {@code size-down} 两个按钮驱动，见 {@link #onSizeUp()}。
     */
    @FXML
    private ComboBox<String> fontCombo;
    @FXML
    private ColorPicker colorPicker;
    @FXML
    private MenuButton alignButton;
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

    // 「视图」菜单里的预览命令：有书才有意义，首页（书架）收起、只留主题三项。
    // 两条分隔线也要一起收——只藏 MenuItem 会在菜单顶部留两条空档。
    @FXML
    private MenuItem refreshPreviewItem;
    @FXML
    private SeparatorMenuItem previewSeparatorTop;
    @FXML
    private MenuItem splitPreviewItem;
    @FXML
    private SeparatorMenuItem previewSeparatorBottom;

    @FXML
    private MenuItem undoItem;
    @FXML
    private MenuItem redoItem;

    // 编辑 / 章节 / 插入 / 工具 四个菜单只对「已打开的图书」有意义——首页（书架）不该展示；
    // 文件 / 帮助 是全局命令，两态都保留；「视图」菜单本身两态都在，但其中「有书才有意义」的
    // 预览命令（刷新预览 / 并排预览 + 两条分隔线）随外壳一起收起，首页只留主题三项。
    // 显隐统一由 setEditorChromeVisible 切换，见该方法注释。
    @FXML
    private Menu editMenu;
    @FXML
    private Menu chapterMenu;
    @FXML
    private Menu insertMenu;
    @FXML
    private Menu toolsMenu;

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
     * 自动暂存节流器 + 状态栏指示灯。实现搬到 {@link AutosaveIndicator}（纯搬迁）；
     * 主控制器只在 {@code markDirty()} 里调 {@link AutosaveIndicator#onDirty()}。
     */
    private AutosaveIndicator autosaveIndicator;

    /**
     * 启动草稿恢复（扫描 → 提示 → 收编进工作空间）。触发点在 {@link #setStage(Stage)} 末尾，
     * 实现搬到 {@link DraftRecoveryActivity}（纯搬迁）。
     */
    private DraftRecoveryActivity draftRecoveryActivity;

    /**
     * 「拖图书文件到窗口即打开」。实现搬到 {@link FileDropActivity}（纯搬迁）。
     */
    private FileDropActivity fileDropActivity;

    /**
     * 当前主题。initialize 时取自持久化配置，切换后预览区与整个界面同步换色。
     */
    private ThemeActivity themeActivity;
    private StatusCoordinator status;
    private InsertActivity insertActivity;
    private WorkspaceActivity workspaceActivity;

    /**
     * 编辑器外壳（活动栏 / 状态栏 / 「编辑 · 章节 · 插入 · 工具」四个菜单）的显隐。
     * 实现搬到 {@link EditorShellActivity}（纯搬迁，拆分批次 C）。
     */
    private EditorShellActivity editorShellActivity;

    /**
     * 预览区：渲染 / 资源镜像 / 并排预览模式。实现搬到 {@link PreviewController}
     * （纯搬迁，拆分批次 C）。构造点必须在任何 {@code refreshPreview()} 调用之前。
     */
    private PreviewController previewController;

    /**
     * 格式化工具条的状态点亮。实现搬到 {@link EditorToolbarController}（纯搬迁，拆分批次 B）。
     * 构造点必须早于 {@link VisualEditorSession}——会话持的是它的方法引用。
     */
    private EditorToolbarController editorToolbarController;

    /**
     * 工具条上的样式控件组（字体 / 字号 / 颜色 / 对齐）。实现搬到
     * {@link EditorStyleControls}。构造点同样必须早于 {@link VisualEditorSession}——
     * 会话持的是它的方法引用（回显通道）。
     */
    private EditorStyleControls editorStyleControls;

    /**
     * 可视化编辑会话（WebView ↔ 正文的双向同步、JS 桥、序列化回写、格式命令）。
     * 实现搬到 {@link VisualEditorSession}（纯搬迁，拆分批次 B）。
     */
    private VisualEditorSession visualEditorSession;

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
        installSceneAccelerators(stage);
        if (draftRecoveryActivity != null) {
            draftRecoveryActivity.promptIfAny();
        }
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
        // 拆分批次 B/C：外壳显隐、预览区、工具条状态、可视化编辑会话各自成类（纯搬迁）。
        // 构造顺序有硬约束——下面把 editorToolbarController::update 交给会话，
        // 方法引用在**求值那一刻**就必须拿到非空实例，所以工具条要先建；
        // previewController 必须早于任何 refreshPreview()（findBar / 主题 / 切 tab 都会调它）。
        editorShellActivity = new EditorShellActivity(activityBar, statusBar,
                editMenu, chapterMenu, insertMenu, toolsMenu,
                // 「视图」菜单的预览命令组，顺序 = FXML 声明顺序（刷新预览 / 分隔线 / 并排预览 / 分隔线）
                List.of(refreshPreviewItem, previewSeparatorTop,
                        splitPreviewItem, previewSeparatorBottom));
        // 资源镜像（预览 / 可视化编辑器里相对引用的解析基准，惰性同步、构造不碰磁盘）
        // 现由 PreviewController 持有，换书时自动清空重建。
        previewController = new PreviewController(ctx, previewView, contentArea,
                editorTabs, splitPreviewPane, splitPreviewItem,
                this::currentChapter, () -> themeActivity.current(),
                SOURCE_TAB_INDEX, PREVIEW_TAB_INDEX);
        editorToolbarController = new EditorToolbarController(editorToolbar);
        // 样式控件（字体 / 文字颜色 / 对齐）：同样要先于会话构造——会话持它的方法引用。
        // 命令经 this::applyVisualFormat 出去，与十二个格式按钮走同一条路。
        editorStyleControls = new EditorStyleControls(fontCombo, colorPicker, alignButton,
                this::applyVisualFormat);
        visualEditorSession = new VisualEditorSession(ctx, visualEditorView, contentArea,
                this::currentChapter, this::previewBaseHref,
                () -> themeActivity.current(),
                // 一次输入编辑步：与源码区共用同一本账（撤销快照 + 600ms 静默合并）
                () -> {
                    ensureUndoActivity();
                    undoActivity.onTextInput();
                },
                () -> {
                    ensureUndoActivity();
                    undoActivity.undo();
                },
                () -> {
                    ensureUndoActivity();
                    undoActivity.redo();
                },
                this::markDirty, () -> status.refresh(),
                editorToolbarController::update,
                editorStyleControls::update);

        // window 在每次文档加载后都是新对象，桥必须跟着重装，否则 loadContent 之后
        // 旧 window 上的 epubraBridge 就没了，页面里的改动再也回不来。
        visualEngine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == javafx.concurrent.Worker.State.SUCCEEDED) {
                visualEditorSession.installBridge();
            }
        });

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
                message -> confirm("请确认", message), status::showError,
                status.progressSink(), this::insertXhtmlIntoActiveEditor,
                // 插图要拿章节 href 算相对路径；这里必须接目录树的「当前章节」，
                // 漏接会让工具条/资源面板插图恒报「请先选择章节」（曾真实发生）
                this::currentChapter);

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

        autosaveIndicator = new AutosaveIndicator(ctx, autosaveStatusLabel);
        autosaveIndicator.wire();

        fileDropActivity = new FileDropActivity(statusLabel, workspaceActivity::openBook);
        fileDropActivity.wireWhenSceneReady();

        // stage 晚绑定（() -> stage），welcomePageController 此时已 fx:include 注入并 bind 完
        draftRecoveryActivity = new DraftRecoveryActivity(ctx,
                status::set, this::warn, () -> stage,
                welcomePageController::showWorkspace, welcomePageController::currentWorkspace,
                () -> setCurrentChapter(null));

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
     * 标题栏 X / Alt+F4 的关闭请求入口。
     *
     * <p>与菜单「退出」（{@link #onExit()} → 确认丢弃 → {@link #dispose()}）的区别：这条路径
     * 不做「未保存的修改将丢弃」确认，因此必须在关窗前把<b>已排定但还没到点</b>的自动暂存
     * 补写一次——否则用户改完立刻关窗，最后那几秒编辑会静默丢失，而工作空间里的
     * {@code .draft} 是文档本体、没有第二份副本可恢复。
     *
     * <p>菜单退出路径<b>不</b>调用本方法：那条路径已经问过用户「是否丢弃修改」，用户选
     * 「继续」就是明确要丢弃，此时再写一次盘会与用户意图相反。
     */
    public void onWindowCloseRequest() {
        if (autosaveIndicator != null) {
            autosaveIndicator.flushPending();
        }
        dispose();
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

    /**
     * 「当前有没有打开的图书」这一个判据驱动的显隐，两组元素一起切：
     *
     * <ol>
     *   <li>编辑器外壳：活动栏 / 状态栏 /「编辑 · 章节 · 插入 · 工具」四个菜单；</li>
     *   <li>「视图」菜单里的预览命令（刷新预览 / 并排预览 + 两条分隔线）——首页只留主题三项。</li>
     * </ol>
     *
     * <p>实现搬到 {@link EditorShellActivity}（纯搬迁，拆分批次 C）；FXML 的
     * {@code onAction} 与事件订阅只能指到主控制器，故这里保留一行委派。
     */
    private void setEditorChromeVisible(boolean visible) {
        editorShellActivity.setVisible(visible);
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
    public void onIndentChapter() {
        tocViewController.onIndentChapter();
    }

    @FXML
    public void onOutdentChapter() {
        tocViewController.onOutdentChapter();
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
     * <p>模式切换与重渲染都在 {@link PreviewController#toggleSplit()}（拆分批次 C）；
     * 状态提示留在这里——状态栏归 {@code StatusCoordinator}，预览类不认识它。
     */
    @FXML
    public void onToggleSplitPreview() {
        boolean split = previewController.toggleSplit();
        status.set(split ? "已切换为并排预览" : "已切换为标签预览");
    }

    // 预览区（渲染 / 相对引用基准 / 并排模式节点搬运）已迁往
    // controller/view/PreviewController（拆分批次 C，纯搬迁）。

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

    // ------------------------------------------------------------------ 活动栏与侧边栏

    /**
     * 安装<b>场景级</b>快捷键：Ctrl+` 切换底部问题面板。
     *
     * <p>这个快捷键原先挂在「视图 → 问题面板」菜单项上。该菜单项已按 UI 精简要求从
     * 「视图」菜单移除（面板仍可由活动栏「校验」按钮打开），快捷键随之迁到这里——
     * 入口收敛了，但功能不该跟着丢。
     *
     * <p>反引号用 {@link KeyCode#BACK_QUOTE} 直接构造：{@code KeyCombination} 的字符串
     * 解析对反引号在不同实现下并不可靠（这正是当初没写进 FXML {@code accelerator=} 的原因）。
     *
     * <p>{@link #setStage(Stage)} 被调用时 stage 还没有 scene（{@code EpubraApp} 的顺序是
     * {@code setStage()} → {@code new Scene()} → {@code stage.setScene()}），所以这里补一个
     * 一次性监听，等 scene 就位再挂。
     */
    private void installSceneAccelerators(Stage stage) {
        KeyCombination toggleProblems =
                new KeyCodeCombination(KeyCode.BACK_QUOTE, KeyCombination.CONTROL_DOWN);
        Scene scene = stage.getScene();
        if (scene != null) {
            scene.getAccelerators().put(toggleProblems, this::onToggleProblems);
            return;
        }
        stage.sceneProperty().addListener((obs, old, now) -> {
            if (now != null) {
                now.getAccelerators().put(toggleProblems, this::onToggleProblems);
            }
        });
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
     * 切换底部问题面板，快捷键 Ctrl+`（见 {@link #installSceneAccelerators(Stage)}）。
     *
     * <p>面板可见性、活动栏按钮选中与立即校验是同一个编排序列，这里交给 sidebar 内部同步；
     * 与 {@link #onShowProblems}（活动栏「校验」按钮）的差别是那个入口的按钮选中态自己维护。
     */
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

    /** 编号列表：可视化编辑器内切换 ol；源码视图则插入一段带编号列表的 XHTML 片段。 */
    @FXML
    public void onInsertOrderedList() {
        if (!applyVisualFormat("ol")) {
            insertActivity.orderedList();
        }
    }

    /** 引用块：可视化编辑器内切换 blockquote；源码视图退化为包一层 {@code blockquote}。 */
    @FXML
    public void onInsertQuote() {
        if (!applyVisualFormat("quote")) {
            insertActivity.wrapTag("blockquote");
        }
    }

    /** 分隔线：可视化编辑器内插入 hr；源码视图插一行自闭合的 hr 片段。 */
    @FXML
    public void onInsertRule() {
        if (!applyVisualFormat("rule")) {
            insertActivity.insertFragment("<hr/>", "<hr/>".length());
        }
    }

    @FXML
    public void onInsertUnderline() {
        if (!applyVisualFormat("underline")) {
            insertActivity.wrapTag("u");
        }
    }

    /** 删除线：可视化编辑器内包裹 del；源码视图用同一标签，保持回写净化口径一致。 */
    @FXML
    public void onInsertStrike() {
        if (!applyVisualFormat("strike")) {
            insertActivity.wrapTag("del");
        }
    }

    @FXML
    public void onInsertCode() {
        if (!applyVisualFormat("code")) {
            insertActivity.wrapTag("code");
        }
    }

    /**
     * 链接弹窗：输入框占整行（标签放输入框上方，而不是 TextInputDialog 的「标签： 输入框」
     * 同行布局），光标已在链接里时回填现有地址，并提供「取消链接」按钮拆掉
     * {@code <a>} 保留文字。确认后交给可视化编辑器把选区（或空选区）包成 {@code <a href>}。
     */
    @FXML
    public void onInsertLink() {
        if (!visualEditorReady()) {
            status.set("编辑视图尚未就绪，请稍后重试");
            return;
        }
        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("插入链接");
        dialog.setHeaderText("输入链接地址；选中的文字将成为链接。光标在链接内时可直接改地址或取消链接。");
        Label label = new Label("链接地址：");
        TextField input = new TextField();
        input.setPromptText("https://example.com/page");
        input.setMaxWidth(Double.MAX_VALUE);
        VBox content = new VBox(6, label, input);
        content.setPadding(new Insets(4, 8, 4, 8));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(420);
        if (stage != null) {
            dialog.initOwner(stage);
        }
        ButtonType unlinkType = new ButtonType("取消链接", ButtonBar.ButtonData.LEFT);
        dialog.getDialogPane().getButtonTypes().addAll(unlinkType, ButtonType.OK, ButtonType.CANCEL);
        // 回填：光标已在链接内时，把现有 href 放进输入框方便直接改
        String currentHref = queryLinkHref();
        if (currentHref != null && !currentHref.isEmpty()) {
            input.setText(currentHref);
        }
        dialog.setResultConverter(type -> {
            if (type == unlinkType) {
                return UNLINK_RESULT;
            }
            if (type.getButtonData() == ButtonBar.ButtonData.OK_DONE) {
                return input.getText();
            }
            return null;
        });
        // 链接地址要随输入可用才点亮 OK：空地址点确认按「地址为空」处理
        input.textProperty().addListener((obs, oldV, newV) ->
                dialog.getDialogPane().lookupButton(ButtonType.OK)
                        .setDisable(newV == null || newV.isBlank()));
        dialog.getDialogPane().lookupButton(ButtonType.OK).setDisable(true);
        Platform.runLater(input::requestFocus);

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        String value = result.get();
        if (UNLINK_RESULT.equals(value)) {
            if (applyVisualFormat("unlink")) {
                status.set("已取消链接");
            } else {
                status.set("光标不在链接内，无法取消链接");
            }
            return;
        }
        String href = value.trim();
        if (href.isEmpty()) {
            status.set("链接地址为空，已取消");
            return;
        }
        if (!applyVisualFormat("link", href)) {
            status.set("链接地址无法使用（不支持 javascript: / data: 这类地址）");
        }
    }

    /** {@link #onInsertLink()} 结果转换里「取消链接」按钮的哨兵值。 */
    private static final String UNLINK_RESULT = "\u0000unlink";

    /** 读取可视化编辑器里光标所在链接的 href；实现搬到 {@link VisualEditorSession#queryLinkHref()}。 */
    private String queryLinkHref() {
        return visualEditorSession.queryLinkHref();
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
        visualEditorSession.invalidate();
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
            visualEditorSession.invalidate();
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

    // JS 桥 / 序列化回写 / 光标处格式命令（含 VisualEditBridge 内部类与源码区同步）
    // 已整体迁往 {@link org.chobit.epubra.app.editor.VisualEditorSession}（拆分批次 B，纯搬迁）。
    // 以下保留一行委派：FXML 的 onAction 与 ResourceController 的 XhtmlInserter 只能指到
    // 主控制器，caller 侧不因此改动。

    /** 把当前章节载入可视化编辑器；实现搬到 {@link VisualEditorSession#reload()}。 */
    private void reloadVisualEditor() {
        // 新文档是全新的一棵树：上一章残留的格式高亮与字体/字号/颜色回显都不再成立。
        // 放在这里而不是各调用点——切章节（showChapter）、撤销回读（reloadEditor）、
        // 切回编辑 tab 三条路都会经过它，漏一处就会显示上一章的样式。
        editorToolbarController.clear();
        editorStyleControls.clear();
        visualEditorSession.reload();
    }

    /** 主动把编辑器里的最新内容拉回正文；实现搬到 {@link VisualEditorSession#flush()}。 */
    private boolean flushVisualEditor() {
        return visualEditorSession.flush();
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
                visualEditorSession.invalidate();
                editorToolbarController.clear();
                editorStyleControls.clear();
            }
            if (index < 0 || currentChapter() == null) {
                return;
            }
            // 源码区的改动此刻只躺在 contentArea 里——文本监听器只做撤销快照与标脏，
            // 不写章节资源；不先落盘的话，reloadVisualEditor / refreshPreview 读到的
            // 仍是修改前的旧文本，表现为「改了源码，编辑/预览不跟新」（#53）。
            // 离开「编辑」tab 时可视化侧已在上方分支 flush 并置 loaded=false，
            // 这里的 flushVisualEditor 必然 no-op，写进资源的就是源码区文本。
            flushCurrentChapter();
            if (index == VISUAL_TAB_INDEX) {
                // reloadVisualEditor 内部会把旧的高亮与样式回显清掉（新树不继承旧状态）
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
    // 命令实现与工具条点亮已分别迁往 editor/VisualEditorSession 与
    // editor/EditorToolbarController（拆分批次 B，纯搬迁）。下面只留一行委派——
    // FXML 的 onAction 与 ResourceController 的 XhtmlInserter 只能指到主控制器。

    /**
     * 字号放大一档（工具条「放大字号」按钮）。
     *
     * <p>档位表与边界处理都在 JS 侧（{@code editor-script.js} 的 {@code SIZE_STEPS} /
     * {@code stepFontSize}）：以<b>当前计算后的字号</b>为锚点跳到下一个更大档，已到顶则
     * 有意不动。返回值这里不看——它属于「样式」而非「插入标签」，编辑器未就绪时不做
     * 源码区兜底（往 XHTML 里塞一个没有对应标签的样式毫无意义），与字体 / 颜色一致。
     */
    @FXML
    public void onSizeUp() {
        applyVisualFormat("size-up");
    }

    /** 字号缩小一档（工具条「缩小字号」按钮）；到最小档时有意不动。 */
    @FXML
    public void onSizeDown() {
        applyVisualFormat("size-down");
    }

    /**
     * 对可视化编辑器施加一次富文本操作。
     *
     * @param kind 格式名（{@code paragraph} / {@code heading} / … / {@code link} / {@code unlink}）
     * @return 是否真的改了内容；false 表示编辑视图还没就绪或命令被拒绝
     */
    private boolean applyVisualFormat(String kind) {
        return visualEditorSession.format(kind);
    }

    /** 带参数的格式化命令（目前只有 {@code link} 需要 href）。 */
    private boolean applyVisualFormat(String kind, String value) {
        return visualEditorSession.format(kind, value);
    }

    /** 可视化编辑器是否可用于施加上下文命令（已加载完成且内容对应当前章节）。 */
    private boolean visualEditorReady() {
        return visualEditorSession.ready();
    }

    /**
     * 把一段 XHTML 片段插到可视化编辑器的光标处（图片等）。
     *
     * <p>片段经 {@code window} 上的临时成员传入，不拼脚本字符串——标签里带引号。
     */
    private boolean insertHtmlIntoVisualEditor(String xhtml) {
        return visualEditorSession.insertHtml(xhtml);
    }

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
        if (onVisualTab()) {
            // 新插的图不在镜像里（sync 只镜像「章节可达」资源，章节文本此刻尚未回写），
            // 不补写的话 img 进了 DOM 也解析不到文件——用户看到的就是「插了却不显示」
            mirrorImagesReferencedBy(xhtml);
            if (insertHtmlIntoVisualEditor(xhtml)) {
                return true;
            }
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

    /** 补写预览镜像；实现搬到 {@link PreviewController#mirrorImagesReferencedBy(String)}（批次 C）。 */
    private void mirrorImagesReferencedBy(String xhtml) {
        previewController.mirrorImagesReferencedBy(xhtml);
    }

    // 「编辑视图当前内容是否对应当前章节」的 loaded 标记已随会话迁往 VisualEditorSession
    // （拆分批次 B）——它必须与 JS 桥、序列化回写同处一地，散在外面迟早会被漏掉某处重置。

    /** 重渲染预览区；实现搬到 {@link PreviewController#refresh()}（拆分批次 C，纯搬迁）。 */
    private void refreshPreview() {
        previewController.refresh();
    }

    /** 相对引用的解析基准；实现搬到 {@link PreviewController#baseHref(ChapterNode)}（拆分批次 C）。 */
    private String previewBaseHref(ChapterNode current) {
        return previewController.baseHref(current);
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
        // 判空是给「autosaveIndicator 接好之前的早期改动」留的：搬迁前这里的条件是
        // autosaveDebounce != null，同样是空安全的，不能因为换了持有者就漏掉这层保护。
        if (autosaveIndicator != null) {
            autosaveIndicator.onDirty();
        }
        status.refresh();
    }

}
