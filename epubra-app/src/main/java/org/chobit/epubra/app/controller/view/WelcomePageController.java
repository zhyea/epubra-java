package org.chobit.epubra.app.controller.view;

import org.chobit.epubra.app.ui.support.context.AppEventBus;
import org.chobit.epubra.app.ui.support.context.BookContext;
import org.chobit.epubra.app.support.workspace.RecentProjectsStore;
import javafx.fxml.FXML;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * 启动欢迎页：用户首次进入应用看到的就是这一屏——「新建项目」「打开 EPUB」「最近的项目」。
 *
 * <p>来源 {@code welcome-page.fxml}，由 main-window.fxml 以 {@code <fx:include fx:id="welcomePage"/>}
 * 引入，覆盖在编辑区之上。父控制器 {@code MainController} 在 {@code initialize()} 内
 * 调本类的 {@link #bind} 注入运行时依赖。
 *
 * <h2>可见性协议</h2>
 * <ul>
 *   <li>FXML 启动时 {@code visible="true"}——欢迎页就是初始视图</li>
 *   <li>{@link #subscribeVisibility} 订阅 {@link AppEventBus.BookLoadedEvent}——
 *       一旦有书载入就 hide，让位给正常的编辑面板</li>
 * </ul>
 *
 * <p>本类不实现 {@code initialize()}——所有 setup 在父控制器 bind 时按需触发。
 */
public class WelcomePageController {

    /** 最大展示的最近项目 / 工作空间条目数。 */
    private static final int MAX_RECENTS = 6;

    @FXML
    private StackPane welcomeRoot;
    @FXML
    private VBox welcomePane;
    @FXML
    private Button newProjectBtn;
    @FXML
    private Button openFileBtn;
    @FXML
    private VBox recentProjectsList;
    @FXML
    private VBox recentWorkspacesList;
    @FXML
    private Label recentEmptyHint;
    @FXML
    private Node recentSection;

    private Runnable onNewProject;
    private Runnable onOpenFile;
    private Consumer<Path> onOpenRecent;
    private Runnable onExit;

    private AppEventBus.Unsubscriber bookLoadedUnsubscriber;

    /**
     * 父控制器在 FXML 加载完成后注入回调：本类只负责把按钮事件转发出去。
     * 任何非 null 的回调都必须设置后再调用展示/隐藏/重画。
     */
    public void bind(Runnable onNewProject, Runnable onOpenFile,
                     Consumer<Path> onOpenRecent, Runnable onExit) {
        this.onNewProject = onNewProject;
        this.onOpenFile = onOpenFile;
        this.onOpenRecent = onOpenRecent;
        this.onExit = onExit;
        rebuildRecents(); // bind 时刷一次，确保第一次显示就有最新数据
    }

    /**
     * 订阅 {@link AppEventBus.BookLoadedEvent} 自动收起欢迎页。只能在 JavaFX 应用
     * 线程上调用——{@code ctx.bus()} 是线程安全的，但欢迎页根节点的可见性是 JavaFX 控件树状态。
     */
    public void subscribeVisibility(BookContext ctx) {
        if (bookLoadedUnsubscriber != null) {
            bookLoadedUnsubscriber.close();
        }
        bookLoadedUnsubscriber = ctx.bus().subscribe(AppEventBus.BookLoadedEvent.class,
                e -> hide());
    }

    @FXML
    private void onNewProjectAction() {
        if (onNewProject != null) {
            onNewProject.run();
        }
    }

    @FXML
    private void onOpenFileAction() {
        if (onOpenFile != null) {
            onOpenFile.run();
        }
    }

    @FXML
    private void onExitAction() {
        if (onExit != null) {
            onExit.run();
        }
    }

    /** 重新扫描两个 Recent 列表，刷新到 UI。 */
    public void rebuildRecents() {
        if (recentProjectsList == null || recentWorkspacesList == null) {
            return;
        }
        // 列表与「无记录时显示提示」的互斥显示——任何一边有内容就隐藏提示
        List<Path> projs = RecentProjectsStore.projects().stream()
                .map(this::tryPath)
                .filter(p -> p != null)
                .limit(MAX_RECENTS)
                .collect(Collectors.toList());
        List<Path> workspaces = RecentProjectsStore.workspaces().stream()
                .map(this::tryPath)
                .filter(p -> p != null)
                .limit(MAX_RECENTS)
                .collect(Collectors.toList());
        populate(recentProjectsList, projs, true);
        populate(recentWorkspacesList, workspaces, false);
        boolean anyEntry = !projs.isEmpty() || !workspaces.isEmpty();
        if (recentEmptyHint != null) {
            recentEmptyHint.setVisible(!anyEntry);
            recentEmptyHint.setManaged(!anyEntry);
        }
        if (recentSection != null) {
            recentSection.setVisible(anyEntry);
            recentSection.setManaged(anyEntry);
        }
    }

    /** 把路径转 Path，路径有问题（删除 / 非目录）一律返回 null——让 rebuildRecents 自然过滤。 */
    private Path tryPath(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return Path.of(s);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 用一组条目填充 VBox 列表；{@code isProject} 决定条目是项目（.epub）还是工作空间（目录），
     * 影响点击行为与图标。
     */
    private void populate(VBox list, List<Path> paths, boolean isProject) {
        list.getChildren().clear();
        for (Path p : paths) {
            Button btn = new Button();
            // 标题行：文件名 / 工作空间根目录名
            btn.setText(p.getFileName() == null ? p.toString() : p.getFileName().toString());
            String fullPath = p.toString();
            String hint;
            if (isProject) {
                hint = p.getParent() == null ? "" : p.getParent().toString();
                btn.getStyleClass().add("welcome-recent-project");
            } else {
                hint = isExistingDir(p) ? "工作空间" : "（目录不存在）";
                btn.getStyleClass().add("welcome-recent-workspace");
            }
            // 完整路径/状态作为提示
            if (!hint.isBlank()) {
                btn.setTooltip(new javafx.scene.control.Tooltip(fullPath + "\n" + hint));
            }
            btn.setOnAction(e -> {
                if (onOpenRecent != null) {
                    onOpenRecent.accept(p);
                }
            });
            btn.setMaxWidth(Double.MAX_VALUE);
            list.getChildren().add(btn);
        }
    }

    private boolean isExistingDir(Path p) {
        return Files.isDirectory(p);
    }

    /**
     * 显式收起欢迎页。
     *
     * <p>必须作用于 {@code fx:include} 的<b>根节点</b>（welcomeRoot StackPane），
     * 不能只藏内层 {@code welcomePane} VBox——JavaFX 的鼠标命中测试只认 visible：
     * 根 StackPane 保持 visible 时，其透明区域（pickOnBounds=true）仍会拦截整个
     * 编辑区的点击，表现为「打开书后左侧按钮 / 编辑区全部无效」。2026-09-06 修复。
     */
    public void hide() {
        if (welcomeRoot == null) {
            return;
        }
        welcomeRoot.setVisible(false);
        welcomeRoot.setManaged(false);
    }

    /** 显式展示欢迎页——为将来「关闭项目」场景预留。 */
    public void show() {
        if (welcomeRoot == null) {
            return;
        }
        rebuildRecents();
        welcomeRoot.setVisible(true);
        welcomeRoot.setManaged(true);
    }

    /** 解绑——绑定过的 JavaFX 节点还在，但事件订阅已失效。MainController 关闭时调用。 */
    public void dispose() {
        if (bookLoadedUnsubscriber != null) {
            bookLoadedUnsubscriber.close();
            bookLoadedUnsubscriber = null;
        }
    }

    /** 给单元测试用：读最近面板的可见性状态。 */
    public boolean isVisible() {
        return welcomeRoot != null && welcomeRoot.isVisible();
    }
}
