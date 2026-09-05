package com.epubra.app.controller;

import com.epubra.app.EpubraApp;
import com.epubra.app.support.Autosave;
import com.epubra.app.support.BookContext;
import com.epubra.app.support.ProjectLayout;
import com.epubra.app.support.RecentProjectsStore;
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
    private final FileChooserOpener dialogs;
    private final java.util.function.Consumer<String> errorReporter;

    public DocumentController(BookContext ctx, StatusSink status, DiscardConfirmation discarder,
                              FileChooserOpener dialogs,
                              java.util.function.Consumer<String> errorReporter) {
        this.ctx = ctx;
        this.status = status;
        this.discarder = discarder;
        this.dialogs = dialogs;
        this.errorReporter = errorReporter;
    }

    // ---- FXML 入口 ----

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
        try {
            newProject(res.workspace(), res.name(), res.title());
        } catch (IOException e) {
            errorReporter.accept("创建项目失败：" + e.getMessage());
        }
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

    /**
     * 在选定 workspace 下创建一个新项目脚手架（项目目录 + .epubra/ + project.json），
     * 写入初始 EPUB（绑定 source），并将其载入当前 ctx。
     *
     * <p>本方法不弹任何对话框——UI 入口（菜单 / 欢迎页）应当先收集 workspace/name/title
     * 再调用，避免在新模型与旧「新建空白书籍」混淆。
     *
     * @param workspace 父工作空间目录；若不存在会自动创建
     * @param name      项目目录名 / EPUB 文件名（同一字符串），不可包含文件系统非法字符
     * @param title     EPUB 标题；空字符串回退为 {@code name}
     * @return 创建好的 EPUB 文件路径（== ctx.currentFile()）
     * @throws IOException 任何目录 / 文件 IO 失败
     */
    public Path newProject(Path workspace, String name, String title) throws IOException {
        // 项目已存在 → 直接覆盖式打开会丢用户内容，宁可先报错。
        Path projectDir = ProjectLayout.projectDir(workspace, name);
        if (java.nio.file.Files.exists(projectDir)) {
            throw new IOException("项目目录已存在: " + projectDir);
        }
        ProjectLayout.createProjectScaffolding(workspace, name);
        Book created = ProjectLayout.createInitialEpub(workspace, name, title);
        Path target = ProjectLayout.epubFile(workspace, name);
        ctx.setBook(created);
        ctx.setCurrentFile(target);
        ctx.setCurrentNode(null);
        ctx.setDirty(false);
        ctx.history().reset();
        ctx.setEditCaptured(false);
        ctx.bus().publish(new com.epubra.app.support.AppEventBus.BookLoadedEvent());
        // 落两条 recent：workspace 与打开过的 project 文件
        RecentProjectsStore.addWorkspace(workspace.toString());
        RecentProjectsStore.addProject(target.toString());
        ProjectLayout.touchLastOpened(projectDir);
        status.setStatus("已创建项目 " + projectDir.getFileName());
        return target;
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
        try {
            openFile(file.toPath());
            status.setStatus("已打开 " + file.getName());
        } catch (IOException e) {
            errorReporter.accept("打开失败：无法读取 " + file.getName() + "（" + e.getMessage() + "）");
        }
    }

    public void onSave() {
        if (ctx.currentFile() == null) {
            onSaveAs();
            return;
        }
        saveTo(ctx.currentFile());
    }

    public void onSaveAs() {
        File file = dialogs.showSaveDialog(defaultFileName());
        if (file == null) {
            return;
        }
        saveTo(file.toPath());
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

    public void openFile(Path file) throws IOException {
        Book opened = reader.read(file);
        ctx.setBook(opened);
        ctx.setCurrentFile(file);
        ctx.setCurrentNode(null);
        ctx.setDirty(false);
        ctx.history().reset();
        ctx.setEditCaptured(false);
        ctx.bus().publish(new com.epubra.app.support.AppEventBus.BookLoadedEvent());
        status.setStatus("已打开 " + file.getFileName());
    }

    public void saveTo(Path target) {
        try {
            writer.write(ctx.book(), target);
            ctx.setCurrentFile(target);
            ctx.book().setSource(target);
            ctx.setDirty(false);
            status.setStatus("已保存到 " + target.getFileName());
            ctx.bus().publish(new com.epubra.app.support.AppEventBus.BookSavedEvent());
        } catch (IOException e) {
            errorReporter.accept("保存失败：无法写入 " + target + "（" + e.getMessage() + "）");
        }
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