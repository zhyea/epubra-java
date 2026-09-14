package org.chobit.epubra.app.activities;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.chobit.epubra.app.ui.FxNodes;

import java.util.List;

/**
 * 「当前有没有打开的图书」这一个判据驱动的界面显隐，共两组：
 *
 * <ol>
 *   <li><b>编辑器外壳</b>——活动栏 / 状态栏 /「编辑 · 章节 · 插入 · 工具」四个菜单；</li>
 *   <li><b>「视图」菜单里的预览命令</b>——刷新预览 / 并排预览，连同它们上下两条分隔线。
 *       菜单<b>本身</b>两态都在（主题是全局命令），首页只留主题三项。</li>
 * </ol>
 *
 * <p>搬迁自 {@code MainController}（拆分批次 C，纯搬运），第二批归拢于 2026-09-14。
 *
 * <p>首页是书架（欢迎页），没有打开的图书——这些控件在那里既无操作对象也无意义，一律收起。
 * 启动时与「切回书架」时传 {@code false}；收到 {@code AppEventBus.BookLoadedEvent}
 * （新建 / 打开 / 恢复草稿）时传 {@code true}。文件 / 帮助 / 视图里的主题项是全局命令，不在此列。
 *
 * <p>{@code Menu} 没有 {@code managed} 概念（{@code MenuBar} 本就会过滤掉不可见的菜单），
 * 故走 {@link FxNodes#setVisible(Menu, boolean)} 只切 {@code visible}。
 * 附带效果：{@code MenuBarSkin} 只为可见菜单注册加速键，收起后 Ctrl+F、F2 等编辑类
 * 快捷键在首页随之失效——正是期望行为。
 *
 * <p>{@code MenuItem} 则走原生 {@code setVisible}：{@code ContextMenuContent} 会把每个条目的
 * 容器 {@code visibleProperty} 绑到条目上并在布局时跳过不可见者（含 {@code SeparatorMenuItem}），
 * 所以「收起预览命令」不能只藏两个 MenuItem——留着分隔线会在菜单顶部留两条空档。
 */
public final class EditorShellActivity {

    private final VBox activityBar;
    private final HBox statusBar;
    private final Menu editMenu;
    private final Menu chapterMenu;
    private final Menu insertMenu;
    private final Menu toolsMenu;

    /**
     * 「视图」菜单里的预览命令组，按 FXML 声明顺序：
     * 刷新预览 / 上分隔线 / 并排预览 / 下分隔线。
     */
    private final List<MenuItem> previewCommands;

    public EditorShellActivity(VBox activityBar, HBox statusBar,
                               Menu editMenu, Menu chapterMenu,
                               Menu insertMenu, Menu toolsMenu,
                               List<MenuItem> previewCommands) {
        this.activityBar = activityBar;
        this.statusBar = statusBar;
        this.editMenu = editMenu;
        this.chapterMenu = chapterMenu;
        this.insertMenu = insertMenu;
        this.toolsMenu = toolsMenu;
        this.previewCommands = previewCommands == null ? List.of() : List.copyOf(previewCommands);
    }

    /** 一次切换两组元素的显隐；节点为 null（FXML 未注入）时静默跳过。 */
    public void setVisible(boolean visible) {
        FxNodes.setVisibleManaged(activityBar, visible);
        FxNodes.setVisibleManaged(statusBar, visible);
        FxNodes.setVisible(editMenu, visible);
        FxNodes.setVisible(chapterMenu, visible);
        FxNodes.setVisible(insertMenu, visible);
        FxNodes.setVisible(toolsMenu, visible);
        for (MenuItem item : previewCommands) {
            if (item != null) {
                item.setVisible(visible);
            }
        }
    }
}
