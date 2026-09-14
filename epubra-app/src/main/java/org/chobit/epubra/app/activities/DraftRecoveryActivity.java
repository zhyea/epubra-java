package org.chobit.epubra.app.activities;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;
import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.document.Autosave;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import org.chobit.epubra.lib.domain.Book;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 启动时扫描可恢复草稿 → 提示 → 收编进工作空间。
 *
 * <p>从 {@code MainController} 搬出（纯搬迁，行为不变）。触发点是
 * {@code MainController.setStage(Stage)} 的末尾，<b>不是</b> {@code initialize()}。
 *
 * <h2>为什么恢复必须绑定工作空间</h2>
 * <p>没有工作空间就无从创建 / 维护图书，反过来"有图书草稿就该有工作空间"。启动扫描命中的
 * 都是<b>未归属工作空间</b>的孤儿草稿（{@code ~/.Epubra/autosave/*.draft}），所以这里的恢复
 * 语义是<b>收编</b>：读出来 → 以书名写成 {@code <workspace>/<书名>.draft} → 清掉孤儿。提示里
 * 必须写明工作空间的名称与完整路径，恢复后 {@code ctx.currentFile()} 指向工作空间里的真实文件
 * （旧实现留 null，造成"恢复出来的书不属于任何工作空间，改完又写回孤儿目录"的死循环）。
 *
 * <h2>为什么用 {@code show()} 而不是 {@code showAndWait()}</h2>
 * <p>启动流程跑在 FX 线程上。{@code showAndWait()} 会开一个嵌套事件循环把 FX 线程停在原地，
 * 一旦没人应答弹窗（无人值守启动、GUI 测试里恰好存在遗留的孤儿草稿），整个应用连同测试一起
 * 挂死。这里改为 {@code show()} + {@code setOnHidden}：弹窗依旧是模态的，用户应答后走同一条
 * 回调，但 FX 线程立即返回。
 */
public final class DraftRecoveryActivity {

    private final BookContext ctx;
    private final Consumer<String> status;
    private final Consumer<String> errorReporter;
    private final Supplier<Stage> stage;
    private final Consumer<Path> showWorkspace;
    private final Supplier<Path> currentWorkspace;
    private final Runnable clearChapterSelection;

    /**
     * @param ctx                   共享状态（读 / 写当前图书）
     * @param status                状态栏提示
     * @param errorReporter         失败提示（原 {@code MainController.warn}）
     * @param stage                 主窗口 stage，弹窗 owner；未注入时返回 null 也可用
     * @param showWorkspace         把首页切到指定工作空间
     * @param currentWorkspace      首页当前工作空间，用于判断是否需要切换
     * @param clearChapterSelection 收编后清空目录树选中（原 {@code setCurrentChapter(null)}）
     */
    public DraftRecoveryActivity(BookContext ctx,
                                 Consumer<String> status,
                                 Consumer<String> errorReporter,
                                 Supplier<Stage> stage,
                                 Consumer<Path> showWorkspace,
                                 Supplier<Path> currentWorkspace,
                                 Runnable clearChapterSelection) {
        this.ctx = ctx;
        this.status = status;
        this.errorReporter = errorReporter;
        this.stage = stage;
        this.showWorkspace = showWorkspace;
        this.currentWorkspace = currentWorkspace;
        this.clearChapterSelection = clearChapterSelection;
    }

    /** 发现孤儿草稿就弹 Alert，让用户选恢复到哪个工作空间。 */
    public void promptIfAny() {
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
        Stage owner = stage.get();
        if (owner != null) {
            alert.initOwner(owner);
        }
        ButtonType acceptBtn = new ButtonType(
                workspace == null ? "选择工作空间并恢复" : "恢复到此工作空间");
        ButtonType discardBtn = new ButtonType("丢弃");
        alert.getButtonTypes().setAll(acceptBtn, discardBtn);
        alert.setOnHidden(event -> onChoice(file, workspace, alert.getResult(), acceptBtn));
        alert.show();
    }

    /** 恢复弹窗的应答处理：接受 → 收编进工作空间；其它（含"丢弃"/直接关窗）→ 删掉孤儿。 */
    private void onChoice(Path file, Path workspace, ButtonType result, ButtonType acceptBtn) {
        if (result != acceptBtn) {
            // 丢弃：删掉孤儿草稿，让后续启动不再反复提示。
            deleteQuietly(file);
            return;
        }
        Path targetWorkspace = workspace != null ? workspace : chooseWorkspace();
        if (targetWorkspace == null) {
            // 没有工作空间就无从归属——保留草稿，下次启动再提示。
            status.accept("未选择工作空间，草稿仍保留在自动暂存目录");
            return;
        }
        adoptOrphanDraft(file, targetWorkspace);
    }

    /** 让用户为孤儿草稿挑一个工作空间；取消时返回 null。 */
    private Path chooseWorkspace() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("选择草稿要恢复到的工作空间");
        workspacePathHint().ifPresent(dir -> chooser.setInitialDirectory(dir.toFile()));
        File selected = chooser.showDialog(stage.get());
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
            clearChapterSelection.run();
            ctx.setDirty(true);
            ctx.history().reset();
            ctx.setEditCaptured(false);
            Path shown = currentWorkspace.get();
            if (!workspace.equals(shown)) {
                showWorkspace.accept(workspace);
            }
            ctx.bus().publish(new AppEventBus.BookLoadedEvent());
            status.accept("已把草稿恢复到工作空间：" + target.getFileName());
        } catch (IOException e) {
            errorReporter.accept("草稿恢复失败：" + e.getMessage());
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            System.getLogger(DraftRecoveryActivity.class.getName())
                    .log(System.Logger.Level.WARNING,
                            "Failed to discard draft: " + e.getMessage(), e);
        }
    }
}
