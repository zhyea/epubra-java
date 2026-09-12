package org.chobit.epubra.app.activities;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.document.Autosave;
import org.chobit.epubra.app.workspace.WorkspaceStore;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 工作空间切换编排：「最近的工作空间」菜单、目录选择、切走前丢弃确认、打开草稿。
 *
 * <p>从 {@code MainController} 拆出：这一组只围绕「当前在哪个工作空间」转，
 * 与编辑器内部流程（章节 / 校验 / 查找）无关。
 *
 * <p>回调一律用 {@link Supplier} / {@link Consumer} 传入：{@code stage} 是 FXML 加载后才
 * 注入的晚绑定值，而丢弃确认、文档打开这些能力仍留在 {@code MainController}。
 */
public class WorkspaceActivity {

    private final BookContext ctx;
    private final Menu recentWorkspaceMenu;
    private final Supplier<Stage> stage;
    private final BooleanSupplier confirmDiscard;
    private final Consumer<String> warn;
    private final Runnable onBeforeSwitch;   // 关闭当前书：清 currentChapter 等
    private final Consumer<Path> onShowWorkspace;
    private final Consumer<Path> openDraftFile;
    private final Consumer<Path> openBookFile;
    private final Runnable onLeftWorkspace;  // 切回书架：隐藏编辑区外壳

    public WorkspaceActivity(BookContext ctx, Menu recentWorkspaceMenu, Supplier<Stage> stage,
                             BooleanSupplier confirmDiscard, Consumer<String> warn,
                             Runnable onBeforeSwitch, Consumer<Path> onShowWorkspace,
                             Consumer<Path> openDraftFile, Consumer<Path> openBookFile,
                             Runnable onLeftWorkspace) {
        this.ctx = ctx;
        this.recentWorkspaceMenu = recentWorkspaceMenu;
        this.stage = stage;
        this.confirmDiscard = confirmDiscard;
        this.warn = warn;
        this.onBeforeSwitch = onBeforeSwitch;
        this.onShowWorkspace = onShowWorkspace;
        this.openDraftFile = openDraftFile;
        this.openBookFile = openBookFile;
        this.onLeftWorkspace = onLeftWorkspace;
    }

    /** 重建「最近的工作空间」子菜单；为空时放一条禁用占位项。 */
    public void refreshRecentMenu() {
        if (recentWorkspaceMenu == null) {
            return;
        }
        recentWorkspaceMenu.getItems().clear();
        List<Path> recent = WorkspaceStore.recentExisting();
        if (recent.isEmpty()) {
            MenuItem empty = new MenuItem("暂无最近工作空间");
            empty.setDisable(true);
            recentWorkspaceMenu.getItems().add(empty);
            return;
        }
        for (Path workspace : recent) {
            MenuItem item = new MenuItem(displayName(workspace));
            item.setOnAction(event -> switchTo(workspace));
            item.setMnemonicParsing(false);
            recentWorkspaceMenu.getItems().add(item);
        }
    }

    /** 弹目录选择器切换工作空间。 */
    public void chooseAndSwitch() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("打开工作空间");
        Stage s = stage.get();
        if (s != null && WorkspaceStore.last().isPresent()
                && Files.isDirectory(WorkspaceStore.last().orElseThrow())) {
            chooser.setInitialDirectory(WorkspaceStore.last().orElseThrow().toFile());
        }
        File selected = chooser.showDialog(s);
        if (selected != null) {
            switchTo(selected.toPath());
        }
    }

    /**
     * 切换到指定工作空间：先确认丢弃当前未保存内容，再清掉旧书状态、记住目录、
     * 让首页展示新工作空间并收起编辑区外壳。
     */
    public void switchTo(Path workspace) {
        if (workspace == null || !Files.isDirectory(workspace)) {
            warn.accept("工作空间不存在：" + workspace);
            refreshRecentMenu();
            return;
        }
        if (ctx.book() != null && !confirmDiscard.getAsBoolean()) {
            return;
        }
        if (ctx.book() != null) {
            Autosave.discardFor(ctx);
            ctx.setBook(null);
            ctx.setCurrentFile(null);
            ctx.resetForNewBook();
            onBeforeSwitch.run();
        }
        WorkspaceStore.add(workspace);
        onShowWorkspace.accept(workspace);
        onLeftWorkspace.run();
        refreshRecentMenu();
    }

    /** 打开书架里的草稿文档。 */
    public void openDraft(Path draftFile) {
        if (draftFile == null || !Files.isRegularFile(draftFile)) {
            warn.accept("图书文件不存在：" + draftFile);
            return;
        }
        if (!confirmDiscard.getAsBoolean()) {
            return;
        }
        Autosave.discardFor(ctx);
        openDraftFile.accept(draftFile);
    }

    /** 打开拖放进来的图书文件。 */
    public void openBook(Path file) {
        if (!confirmDiscard.getAsBoolean()) {
            return;
        }
        Autosave.discardFor(ctx);
        openBookFile.accept(file);
    }

    public static String displayName(Path workspace) {
        if (workspace == null || workspace.getFileName() == null) {
            return workspace == null ? "" : workspace.toString();
        }
        return workspace.getFileName().toString();
    }
}
