package com.epubra.app.controller;

import com.epubra.app.EpubraApp;
import com.epubra.app.support.AsyncTasks;
import com.epubra.app.support.Autosave;
import com.epubra.app.support.BookContext;
import com.epubra.app.support.ProjectLayout;
import com.epubra.app.support.RecentProjectsStore;
import com.epubra.app.support.WorkspaceStore;
import com.epubra.epublib.domain.Book;
import com.epubra.epublib.io.EpubReader;
import com.epubra.epublib.io.EpubWriter;
import com.epubra.epublib.util.Hrefs;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 文件 IO 控制器：新建 / 打开 / 保存 / 另存为 / 退出 / 关于。
 *
 * <p>持有 {@link BookContext}，对外只暴露语义清晰的方法；{@link MainController} 在
 * FXML 回调里直接转发，不做业务判断。
 *
 * <p>B1 起，3 个文件 IO 操作（{@code openFile} / {@code saveTo} / {@code newProject}）
 * 走 {@link AsyncTasks} 后台执行：
 * <ul>
 *   <li>UI 入口（菜单 / 欢迎页 / 拖放）调 {@code openFileAsync} / {@code saveToAsync} /
 *       {@code newProjectAsync}，立即返回不阻塞 FX 线程；</li>
 *   <li>同步版 {@code openFile(Path)} / {@code newProject(Path, String, String)} 保留，
 *       仅供单元测试与「拖放 → 直接打开」之类的原子路径调用，自身仍然阻塞；</li>
 *   <li>{@code saveTo(Path)} 同步版已弃用——保留方法签名但抛
 *       {@link UnsupportedOperationException}，引导使用方切到 {@code saveToAsync}。</li>
 * </ul>
 */
public class DocumentController {

    @FunctionalInterface
    public interface StatusSink {
        void setStatus(String message);
    }

    /** 「继续操作将丢弃修改」二次确认。 */
    @FunctionalInterface
    public interface DiscardConfirmation {
        /** @return true 表示用户确认丢弃，可继续；false 表示取消。 */
        boolean confirmDiscard();
    }

    /** 文件对话框需要 JavaFX Stage；留给调用方注入，避免 DocumentController 强依赖。 */
    public interface FileChooserOpener {
        File showOpenDialog();
        File showSaveDialog(String initialName);
    }

    private final BookContext ctx;
    private final EpubReader reader = new EpubReader();
    private final EpubWriter writer = new EpubWriter();
    private final StatusSink status;
    private final DiscardConfirmation discarder;
    private final AsyncTasks.ProgressController progress;
    private final FileChooserOpener dialogs;
    private final java.util.function.Consumer<String> errorReporter;

    public DocumentController(BookContext ctx, StatusSink status, DiscardConfirmation discarder,
                              FileChooserOpener dialogs,
                              AsyncTasks.ProgressController progress,
                              java.util.function.Consumer<String> errorReporter) {
        this.ctx = ctx;
        this.status = status;
        this.discarder = discarder;
        this.dialogs = dialogs;
        this.progress = progress;
        this.errorReporter = errorReporter;
    }

    // ---- FXML 入口（异步版本：B1 落地） ----

    public void onNew() {
        if (!discarder.confirmDiscard()) {
            return;
        }
        // IDEA 风格的新建：工作空间 + 项目名 + 标题，三项校验通过后才落盘
        Autosave.discardFor(ctx);
        Path initialWorkspace = MostRecentWorkspace(); // may be null
        Optional<NewProjectResult> picked = NewProjectDialog.show(ctx.stage(), initialWorkspace);
        if (picked.isEmpty()) {
            status.setStatus("已取消新建项目");
            return;
        }
        NewProjectResult res = picked.get();
        newProjectAsync(res.workspace(), res.name(), res.title());
    }

    /** 取最近一次访问的工作空间目录；没有或目录不存在时返回 null。 */
    private static Path MostRecentWorkspace() {
        List<String> recents = RecentProjectsStore.workspaces();
        if (recents.isEmpty()) {
            return null;
        }
        Path candidate = Path.of(recents.get(0));
        return java.nio.file.Files.isDirectory(candidate) ? candidate : null;
    }

    public void onOpen() {
        if (!discarder.confirmDiscard()) {
            return;
        }
        // 同 onNew：用户主动放弃当前 in-memory 内容时，一并清理其草稿
        Autosave.discardFor(ctx);
        File file = dialogs.showOpenDialog();
        if (file == null) {
            return;
        }
        openFileAsync(file.toPath());
    }

    public void onSave() {
        if (ctx.currentFile() == null) {
            onSaveAs();
            return;
        }
        saveToAsync(ctx.currentFile());
    }

    public void onSaveAs() {
        File file = dialogs.showSaveDialog(defaultFileName());
        if (file == null) {
            return;
        }
        saveToAsync(file.toPath());
    }

    public void onExit(Runnable closeStage) {
        if (!discarder.confirmDiscard()) {
            return;
        }
        closeStage.run();
    }

    public void onAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle("关于 " + EpubraApp.APP_NAME);
        alert.setHeaderText(EpubraApp.APP_NAME + " - EPUB 编辑器");
        alert.setContentText("JavaFX 前端 + 项目内自维护的 epublib 内核\n支持 EPUB 2/3 的读取、编辑与写出。");
        if (ctx.stage() != null) {
            alert.initOwner(ctx.stage());
        }
        alert.showAndWait();
    }

    // ---- 异步入口（FXML 走这里） ----

    /**
     * 异步打开 EPUB：FX 线程立即返回；后台线程跑 {@code EpubReader.read}；
     * 完成后切回 FX 线程写入 ctx、广播事件。
     */
    public void openFileAsync(Path file) {
        AsyncTasks.runIo(
                "正在打开 " + file.getFileName(),
                () -> reader.read(file),
                progress,
                opened -> applyOpenedBook(opened, file),
                err -> errorReporter.accept("打开失败：无法读取 " + file.getFileName() + "（" + err.getMessage() + "）")
        );
    }

    // ---- .draft 文档（工作空间里的处理中文档）----

    /**
     * 异步打开工作空间里的 {@code *.draft} 文档并<b>直接进入编辑页</b>——宫格卡片
     * 点击走这条路径，无中间确认页、无二次对话框。
     *
     * <p>与 {@link #openFileAsync(Path)} 的差别只有两处：
     * <ol>
     *   <li>状态栏文案去掉 {@code .draft} 后缀（「已打开 三体」而非「已打开 三体.draft」）；</li>
     *   <li>打开后把<b>文档所在目录</b>记为当前工作空间
     *       （{@link WorkspaceStore#setLast}）——下次启动直达该工作空间。</li>
     * </ol>
     *
     * <p>读取本身与打开 .epub 完全一致：{@code .draft} 的内容就是合法 EPUB zip
     * （"草稿即 EPUB 快照"语义），{@code EpubReader} 无需任何适配。
     *
     * <p>读失败时走 {@code errorReporter} 报错，<b>停留在宫格</b>——不进入一个空的编辑页，
     * 否则用户会以为文档被清空了。
     */
    public void openDraftAsync(Path draftFile) {
        if (draftFile == null) {
            errorReporter.accept("打开失败：文档路径为空");
            return;
        }
        AsyncTasks.runIo(
                "正在打开 " + draftDisplayName(draftFile),
                () -> reader.read(draftFile),
                progress,
                opened -> {
                    applyLoadedBook(opened, draftFile, "已打开 " + draftDisplayName(draftFile));
                    rememberWorkspaceOf(draftFile);
                },
                err -> errorReporter.accept(
                        "打开失败：无法读取 " + draftFile.getFileName() + "（" + err.getMessage() + "）")
        );
    }

    /**
     * 同步打开 {@code *.draft} 文档——单元测试与需要阻塞返回的路径使用。
     *
     * @throws IOException 文档不存在 / 不是合法 EPUB / IO 错误
     */
    public void openDraft(Path draftFile) throws IOException {
        Book opened = reader.read(draftFile);
        applyLoadedBook(opened, draftFile, "已打开 " + draftDisplayName(draftFile));
        rememberWorkspaceOf(draftFile);
    }

    /** 文档显示名：文件名去掉 {@code .draft} 后缀。 */
    public static String draftDisplayName(Path draftFile) {
        if (draftFile == null || draftFile.getFileName() == null) {
            return "";
        }
        return Autosave.stripDraftSuffix(draftFile.getFileName().toString());
    }

    /**
     * 记住文档所在的工作空间目录——下次启动直达。
     *
     * <p>只更新 {@code last}，不写最近列表：用户可能只是从菜单进了个新目录还没"确认"常用，
     * 列表应由显式的「打开工作空间…」动作（{@link WorkspaceStore#add}）维护。
     */
    private static void rememberWorkspaceOf(Path draftFile) {
        Path parent = draftFile.getParent();
        if (parent != null) {
            WorkspaceStore.setLast(parent);
        }
    }

    /**
     * 异步保存到指定路径：后台线程跑 {@code EpubWriter.write}；完成后切回 FX 线程
     * 写 currentFile / dirty / BookSavedEvent。
     *
     * <p><b>并发约束</b>：保存期间用户在编辑器里继续输入，{@code markDirty} 会把
     * {@code ctx.dirty} 重新置 true。保存完成回调里的 {@code setDirty(false)} 不会
     * 清掉用户的新改动——自动暂存节流器会在下一次击键后重启并在 N 秒后再次落盘，
     * 把最新内容写进去。这是把同步版本「先阻塞后清 dirty」的语义放宽为「后台 IO，
     * 并发时由自动暂存兜底」，UX 不退化。
     */
    public void saveToAsync(Path target) {
        Book bookAtStart = ctx.book(); // 后台线程用：避免并发被换书时引用漂移
        if (bookAtStart == null) {
            errorReporter.accept("没有可保存的书籍");
            return;
        }
        AsyncTasks.runIo(
                "正在保存 " + target.getFileName(),
                (java.util.concurrent.Callable<Void>) () -> {
                    writer.write(bookAtStart, target);
                    return null;
                },
                progress,
                ignored -> {
                    ctx.setCurrentFile(target);
                    ctx.book().setSource(target);
                    ctx.setDirty(false);
                    status.setStatus("已保存到 " + target.getFileName());
                    ctx.bus().publish(new com.epubra.app.support.AppEventBus.BookSavedEvent());
                },
                err -> errorReporter.accept("保存失败：无法写入 " + target + "（" + err.getMessage() + "）")
        );
    }

    /**
     * 异步创建新项目：后台线程跑 {@code ProjectLayout.createProjectScaffolding} +
     * {@code ProjectLayout.createInitialEpub}；完成后切回 FX 线程写入 ctx。
     */
    public void newProjectAsync(Path workspace, String name, String title) {
        Path projectDir = ProjectLayout.projectDir(workspace, name);
        // 已在 FX 线程，磁盘检查与后续后台的 ProjectLayout.exists 检查存在小竞态窗口
        // （用户在异步过程中手工建同名目录）；此处先在 FX 线程预检给出明确错误，
        // ProjectLayout.createProjectScaffolding 自身还有兜底。
        if (java.nio.file.Files.exists(projectDir)) {
            errorReporter.accept("创建项目失败：项目目录已存在 " + projectDir);
            return;
        }
        final String finalTitle = title == null || title.isBlank() ? name : title;
        AsyncTasks.runIo(
                "正在创建项目 " + name,
                () -> {
                    ProjectLayout.createProjectScaffolding(workspace, name);
                    return ProjectLayout.createInitialEpub(workspace, name, finalTitle);
                },
                progress,
                created -> {
                    Path target = ProjectLayout.epubFile(workspace, name);
                    applyLoadedBook(created, target, "已创建项目 " + projectDir.getFileName());
                    RecentProjectsStore.addWorkspace(workspace.toString());
                    RecentProjectsStore.addProject(target.toString());
                    ProjectLayout.touchLastOpened(projectDir);
                },
                err -> errorReporter.accept("创建项目失败：" + err.getMessage())
        );
    }

    /**
     * FX 线程跑的回调：把 {@code openFileAsync} 读到的 book 落到 ctx。
     *
     * <p>openFileAsync 与 newProjectAsync 都需要把当前 ctx 重置到新书上——
     * 抽出 {@code applyLoadedBook} 让两条路径用同一份语义，避免一处改了别处忘改。
     */
    private void applyLoadedBook(Book book, Path file, String statusMessage) {
        ctx.setBook(book);
        ctx.setCurrentFile(file);
        ctx.setCurrentNode(null);
        ctx.setDirty(false);
        ctx.history().reset();
        ctx.setEditCaptured(false);
        ctx.bus().publish(new com.epubra.app.support.AppEventBus.BookLoadedEvent());
        status.setStatus(statusMessage);
    }

    /**
     * {@link #openFileAsync} 成功后的 FX 线程回调。
     * 拆成独立方法仅为保留「已打开 X」与「已创建项目 X」两条不同状态文案——共用
     * {@link #applyLoadedBook} 的 ctx 重置逻辑。
     */
    private void applyOpenedBook(Book opened, Path file) {
        applyLoadedBook(opened, file, "已打开 " + file.getFileName());
    }

    // ---- 编程入口：用于单元测试 / 其它控制器复用 ----

    public void newBook() {
        Book fresh = com.epubra.epublib.domain.BookFactory.createEmpty("新书籍");
        ctx.setBook(fresh);
        ctx.setCurrentFile(null);
        ctx.setCurrentNode(null);
        ctx.resetForNewBook();
        ctx.bus().publish(new com.epubra.app.support.AppEventBus.BookLoadedEvent());
        status.setStatus("已新建空白书籍");
    }

    /**
     * 同步打开 EPUB：测试与原子路径（如拖放）使用，FX 线程会被阻塞。
     *
     * <p>生产 UI 入口走 {@link #openFileAsync(Path)}；本方法不再被 {@code onOpen} 调。
     */
    public void openFile(Path file) throws IOException {
        Book opened = reader.read(file);
        applyOpenedBook(opened, file);
    }

    /**
     * 同步创建项目：测试使用，FX 线程会被阻塞。生产 UI 入口走
     * {@link #newProjectAsync(Path, String, String)}。
     *
     * @throws IOException 任何目录 / 文件 IO 失败
     */
    public Path newProject(Path workspace, String name, String title) throws IOException {
        Path projectDir = ProjectLayout.projectDir(workspace, name);
        if (java.nio.file.Files.exists(projectDir)) {
            throw new IOException("项目目录已存在: " + projectDir);
        }
        ProjectLayout.createProjectScaffolding(workspace, name);
        Book created = ProjectLayout.createInitialEpub(workspace, name, title);
        Path target = ProjectLayout.epubFile(workspace, name);
        applyLoadedBook(created, target, "已创建项目 " + projectDir.getFileName());
        RecentProjectsStore.addWorkspace(workspace.toString());
        RecentProjectsStore.addProject(target.toString());
        ProjectLayout.touchLastOpened(projectDir);
        return target;
    }

    /**
     * 同步保存：B1 起弃用。{@code saveToAsync} 已替代之，UI 入口不再调用本方法。
     * 保留方法签名仅为兼容旧测试——实际不再产生调用。
     *
     * @deprecated 用 {@link #saveToAsync(Path)}
     */
    @Deprecated
    public void saveTo(Path target) {
        throw new UnsupportedOperationException(
                "DocumentController.saveTo 已弃用——UI 走 saveToAsync(Path)；测试不应再调此方法");
    }

    /** 给 MainController 在 FXML 初始化时挂一个标准 FileChooser。 */
    public static FileChooserOpener defaultDialogs() {
        return defaultDialogs(null);
    }

    public static FileChooserOpener defaultDialogs(javafx.stage.Stage stage) {
        return new FileChooserOpener() {
            @Override
            public File showOpenDialog() {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("打开 EPUB 文件");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("EPUB 文件", "*.epub"));
                return chooser.showOpenDialog(stage);
            }

            @Override
            public File showSaveDialog(String initialName) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("保存 EPUB 文件");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("EPUB 文件", "*.epub"));
                chooser.setInitialFileName(initialName);
                return chooser.showSaveDialog(stage);
            }
        };
    }

    private String defaultFileName() {
        String title = ctx.book().metadata().firstTitle().isBlank() ? "新书籍" : ctx.book().metadata().firstTitle();
        return title.replaceAll("[\\\\/:*?\"<>|]", "_") + ".epub";
    }
}