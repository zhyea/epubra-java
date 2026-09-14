package org.chobit.epubra.app.activities;

import javafx.scene.control.Menu;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import org.chobit.epubra.app.ui.FxNodes;

/**
 * 编辑器外壳（活动栏 / 状态栏 / 「编辑 · 章节 · 插入 · 工具」四个菜单）的整体显隐。
 *
 * <p>搬迁自 {@code MainController}（拆分批次 C，纯搬运）。归属理由：这六个控件总是一起亮灭，
 * 且判据只有一个——「当前有没有打开的图书」。
 *
 * <p>首页是书架（欢迎页），没有打开的图书——这些控件在那里既无操作对象也无意义，一律收起。
 * 启动时与「切回书架」时传 {@code false}；收到 {@code AppEventBus.BookLoadedEvent}
 * （新建 / 打开 / 恢复草稿）时传 {@code true}。文件 / 视图 / 帮助 是全局命令，不在此列。
 *
 * <p>{@code Menu} 没有 {@code managed} 概念（{@code MenuBar} 本就会过滤掉不可见的菜单），
 * 故走 {@link FxNodes#setVisible(Menu, boolean)} 只切 {@code visible}。
 * 附带效果：{@code MenuBarSkin} 只为可见菜单注册加速键，收起后 Ctrl+F、F2 等编辑类
 * 快捷键在首页随之失效——正是期望行为。
 */
public final class EditorShellActivity {

    private final VBox activityBar;
    private final HBox statusBar;
    private final Menu editMenu;
    private final Menu chapterMenu;
    private final Menu insertMenu;
    private final Menu toolsMenu;

    public EditorShellActivity(VBox activityBar, HBox statusBar,
                               Menu editMenu, Menu chapterMenu,
                               Menu insertMenu, Menu toolsMenu) {
        this.activityBar = activityBar;
        this.statusBar = statusBar;
        this.editMenu = editMenu;
        this.chapterMenu = chapterMenu;
        this.insertMenu = insertMenu;
        this.toolsMenu = toolsMenu;
    }

    /** 一次切换六件套的显隐；节点为 null（FXML 未注入）时静默跳过。 */
    public void setVisible(boolean visible) {
        FxNodes.setVisibleManaged(activityBar, visible);
        FxNodes.setVisibleManaged(statusBar, visible);
        FxNodes.setVisible(editMenu, visible);
        FxNodes.setVisible(chapterMenu, visible);
        FxNodes.setVisible(insertMenu, visible);
        FxNodes.setVisible(toolsMenu, visible);
    }
}
