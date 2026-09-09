package org.chobit.epubra.app.activities;

import org.chobit.epubra.app.EpubraApp;
import org.chobit.epubra.app.components.TextChapterImporter;
import org.chobit.epubra.app.ui.dialog.NewDraftDialog;
import org.chobit.epubra.app.ui.model.NewDraftResult;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.app.context.AppEventBus;
import org.chobit.epubra.app.document.Autosave;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.io.EpubWriter;
import javafx.scene.control.Alert;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 文件 IO 控制器：新建 / 打开 / 保存 / 另存为 / 退出 / 关于。
 *
 * <p>持有 {@link BookContext}，对外只暴露语义清晰的方法；主窗口控制器在
 * FXML 回调里直接转发，不做业务判断。
 *
 * <p>打开和新建图书草稿这类可能耗时的文件 IO 走 {@link AsyncTasks} 后台执行：
 * <ul>
 *   <li>UI 入口（菜单 / 欢迎页 / 拖放）调 {@code openFileAsync} /
 *       {@code newDraftAsync}，立即返回不阻塞 FX 线程；</li>
 *   <li>同步版 {@code openFile(Path)} / {@code newDraft(Path, String, String)} 保留，
 *       仅供单元测试与「拖放 → 直接打开」之类的原子路径调用，自身仍然阻塞；</li>
 *   <li>保存和另存为保持同步，确保写盘与当前文档状态更新在同一条调用链中完成。</li>
 * </ul>
 */
public class DocumentActivity {

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

    /** 文件对话框需要 JavaFX Stage；留给调用方注入，避免 DocumentActivity 强依赖。 */
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
    private Stage stage;

    public DocumentActivity(BookContext ctx, StatusSink status, DiscardConfirmation discarder,
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

    /** 由前端 controller 注入窗口 owner；窗口不属于 BookContext。 */
    public void setStage(Stage stage) {
        this.stage = stage;
    }

    // ---- FXML 入口（异步版本：B1 落地） ----

    public void onNew() {
        if (!discarder.confirmDiscard()) {
            return;
        }
        Autosave.discardFor(ctx);
        Path initialWorkspace = resolveInitialWorkspace();
        Optional<NewDraftResult> picked = NewDraftDialog.show(stage, initialWorkspace);
        if (picked.isEmpty()) {
            status.setStatus("已取消新建图书");
            return;
        }
        NewDraftResult res = picked.get();
        if (res.mode() == NewDraftResult.Mode.EMPTY) {
            newDraftAsync(res.workspace(), res.name(), res.title());
        } else {
            importDraftAsync(res.workspace(), res.name(), res.title(), res.mode(), res.source());
        }
    }

    /** 取最近一次访问的工作空间目录；没有或目录不存在时返回 null。 */
    private static Path resolveInitialWorkspace() {
        return WorkspaceStore.last()
                .or(() -> WorkspaceStore.recentExisting().stream().findFirst())
                .orElse(null);
    }

    public void onOpen() {
        if (!discarder.confirmDiscard()) {
            return;
        }
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
        alert.setContentText("JavaFX 前端 + epublib 内核\n支持 EPUB 2/3 的读取、编辑与写出。");
        if (stage != null) {
            alert.initOwner(stage);
        }
        alert.showAndWait();
    }

    // ---- 异步入口（FXML 走这里） ----

    /**
     * 异步打开 EPUB：FX 线程立即返回；后台线程跑 {@code EpubReader.read}；
     * 完成后切回 FX 线程写入 ctx、广播事件。
     */
    public void openFileAsync(Path file) {
        Path target = workingDraftTarget(file);
        AsyncTasks.runIo(
                "正在打开 " + draftDisplayName(target),
                () -> readIntoDraft(file, target),
                progress,
                opened -> {
                    applyLoadedBook(opened, target, "已打开 " + draftDisplayName(target));
                    rememberWorkspaceOf(target);
                },
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
            WorkspaceStore.add(parent);
        }
    }

    /**
     * 异步创建新图书草稿：后台线程创建并写入 {@code *.draft}；完成后切回 FX 线程写入 ctx。
     */
    public void newDraftAsync(Path workspace, String name, String title) {
        Path draftFile = draftFile(workspace, name);
        if (java.nio.file.Files.exists(draftFile)) {
            errorReporter.accept("创建图书失败：文件已存在 " + draftFile);
            return;
        }
        final String finalTitle = title == null || title.isBlank() ? name : title;
        AsyncTasks.runIo(
                "正在创建图书 " + name,
                () -> {
                    Book created = BookFactory.createEmpty(finalTitle);
                    created.setSource(draftFile);
                    new EpubWriter().write(created, draftFile);
                    return created;
                },
                progress,
                created -> {
                    applyLoadedBook(created, draftFile, "已创建图书 " + draftFile.getFileName());
                    WorkspaceStore.add(workspace);
                },
                err -> errorReporter.accept("创建图书失败：" + err.getMessage())
        );
    }

    /**
     * FX 线程跑的回调：把读取到的 book 落到 ctx。
     *
     * <p>openFileAsync 与 newDraftAsync 都需要把当前 ctx 重置到新书上——
     * 抽出 {@code applyLoadedBook} 让两条路径用同一份语义，避免一处改了别处忘改。
     */
    private void applyLoadedBook(Book book, Path file, String statusMessage) {
        ctx.setBook(book);
        ctx.setCurrentFile(file);
        if (book != null) {
            book.setSource(file);
        }
        ctx.setDirty(false);
        ctx.history().reset();
        ctx.setEditCaptured(false);
        ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        status.setStatus(statusMessage);
    }

    // ---- 编程入口：用于单元测试 / 其它控制器复用 ----

    public void newBook() {
        Book fresh = BookFactory.createEmpty("新书籍");
        ctx.setBook(fresh);
        ctx.setCurrentFile(null);
        ctx.resetForNewBook();
        ctx.bus().publish(new AppEventBus.BookLoadedEvent());
        status.setStatus("已新建空白书籍");
    }

    /**
     * 同步打开 EPUB：测试与原子路径（如拖放）使用，FX 线程会被阻塞。
     *
     * <p>生产 UI 入口走 {@link #openFileAsync(Path)}；本方法不再被 {@code onOpen} 调。
     */
    public void openFile(Path file) throws IOException {
        Path target = workingDraftTarget(file);
        Book opened = readIntoDraft(file, target);
        applyLoadedBook(opened, target, "已打开 " + draftDisplayName(target));
        rememberWorkspaceOf(target);
    }

    /**
     * 异步导入 EPUB 或 TXT，并把编辑中的副本统一写成工作空间下的 {@code .draft}。
     */
    public void importDraftAsync(Path workspace, String name, String title,
                                 NewDraftResult.Mode mode, Path source) {
        Path draftFile;
        try {
            draftFile = draftFile(workspace, name);
        } catch (RuntimeException e) {
            errorReporter.accept("导入图书失败：" + e.getMessage());
            return;
        }
        if (source == null || mode == null || mode == NewDraftResult.Mode.EMPTY) {
            errorReporter.accept("导入图书失败：导入方式或源文件为空");
            return;
        }
        if (Files.exists(draftFile)) {
            errorReporter.accept("导入图书失败：文件已存在 " + draftFile);
            return;
        }
        AsyncTasks.runIo(
                "正在导入 " + source.getFileName(),
                () -> importToDraft(mode, source, title, draftFile),
                progress,
                imported -> {
                    applyLoadedBook(imported, draftFile, "已导入图书 " + draftFile.getFileName());
                    WorkspaceStore.add(workspace);
                },
                err -> errorReporter.accept("导入图书失败：" + messageOf(err))
        );
    }

    /** 同步导入入口，供非 UI 调用和测试使用。 */
    public Path importDraft(Path workspace, String name, String title,
                            NewDraftResult.Mode mode, Path source) throws IOException {
        Path draftFile = draftFile(workspace, name);
        if (Files.exists(draftFile)) {
            throw new IOException("图书文件已存在: " + draftFile);
        }
        Book imported = importToDraft(mode, source, title, draftFile);
        applyLoadedBook(imported, draftFile, "已导入图书 " + draftFile.getFileName());
        WorkspaceStore.add(workspace);
        return draftFile;
    }

    private Book importToDraft(NewDraftResult.Mode mode, Path source,
                               String title, Path draftFile) throws IOException {
        if (mode == NewDraftResult.Mode.EPUB) {
            Book imported = reader.read(source);
            imported.setSource(draftFile);
            writer.write(imported, draftFile);
            return imported;
        }
        if (mode == NewDraftResult.Mode.TXT) {
            String text = readText(source);
            String finalTitle = title == null || title.isBlank()
                    ? fileStem(source) : title.trim();
            Book imported = bookFromText(finalTitle, text);
            imported.setSource(draftFile);
            writer.write(imported, draftFile);
            return imported;
        }
        throw new IOException("不支持的导入方式: " + mode);
    }

    /** 读取 EPUB，并在源文件不是 .draft 时先落一份编辑副本。 */
    private Book readIntoDraft(Path source, Path target) throws IOException {
        if (isTextFile(source)) {
            String text = readText(source);
            Book imported = bookFromText(fileStem(source), text);
            imported.setSource(target);
            writer.write(imported, target);
            return imported;
        }
        Book opened = reader.read(source);
        if (!source.toAbsolutePath().normalize().equals(target.toAbsolutePath().normalize())) {
            opened.setSource(target);
            writer.write(opened, target);
        }
        return opened;
    }

    private static String readText(Path source) throws IOException {
        byte[] data = Files.readAllBytes(source);
        if (data.length >= 3
                && (data[0] & 0xFF) == 0xEF
                && (data[1] & 0xFF) == 0xBB
                && (data[2] & 0xFF) == 0xBF) {
            return new String(data, 3, data.length - 3, StandardCharsets.UTF_8);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
        }
        if (data.length >= 2 && (data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
            return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
        }
        try {
            return decodeStrict(data, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ignored) {
            // Windows 编辑器常把中文 TXT 保存为本地代码页；GB18030 兼容 GBK。
            return new String(data, Charset.forName("GB18030"));
        }
    }

    private static String decodeStrict(byte[] data, Charset charset)
            throws CharacterCodingException {
        CharBuffer decoded = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data));
        return decoded.toString();
    }

    private static Book bookFromText(String title, String text) {
        return TextChapterImporter.importBook(title, text);
    }

    private static String fileStem(Path source) {
        if (source == null || source.getFileName() == null) {
            return "导入图书";
        }
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static boolean isTextFile(Path source) {
        if (source == null || source.getFileName() == null) {
            return false;
        }
        return source.getFileName().toString().toLowerCase().endsWith(".txt");
    }

    private static String messageOf(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current.getMessage() == null || current.getMessage().isBlank())) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    /**
     * 同步创建图书草稿：测试使用，FX 线程会被阻塞。生产 UI 入口走
     * {@link #newDraftAsync(Path, String, String)}。
     *
     * @throws IOException 任何目录 / 文件 IO 失败
     */
    public Path newDraft(Path workspace, String name, String title) throws IOException {
        Path draftFile = draftFile(workspace, name);
        if (java.nio.file.Files.exists(draftFile)) {
            throw new IOException("图书文件已存在: " + draftFile);
        }
        Book created = BookFactory.createEmpty(title == null || title.isBlank() ? name : title);
        created.setSource(draftFile);
        new EpubWriter().write(created, draftFile);
        applyLoadedBook(created, draftFile, "已创建图书 " + draftFile.getFileName());
        WorkspaceStore.add(workspace);
        return draftFile;
    }

    /** 同步保存到指定路径；保存期间阻塞当前 UI 调用，完成后再更新文档状态。 */
    public void saveTo(Path target) {
        Book book = ctx.book();
        if (book == null) {
            errorReporter.accept("没有可保存的书籍");
            return;
        }
        try {
            writer.write(book, target);
            ctx.setCurrentFile(target);
            book.setSource(target);
            ctx.setDirty(false);
            status.setStatus("已保存到 " + target.getFileName());
            ctx.bus().publish(new AppEventBus.BookSavedEvent());
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
                chooser.setTitle("打开图书草稿");
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("图书草稿", "*.draft"),
                        new FileChooser.ExtensionFilter("EPUB 文件", "*.epub"),
                        new FileChooser.ExtensionFilter("TXT 文件", "*.txt"));
                return chooser.showOpenDialog(stage);
            }

            @Override
            public File showSaveDialog(String initialName) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("保存图书草稿");
                chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("图书草稿", "*.draft"));
                chooser.setInitialFileName(initialName);
                return chooser.showSaveDialog(stage);
            }
        };
    }

    private String defaultFileName() {
        String title = ctx.book().metadata().firstTitle().isBlank() ? "新书籍" : ctx.book().metadata().firstTitle();
        return title.replaceAll("[\\\\/:*?\"<>|]", "_") + Autosave.DRAFT_SUFFIX;
    }

    private static Path draftFile(Path workspace, String name) {
        if (workspace == null) {
            throw new IllegalArgumentException("workspace is null");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("draft name is empty");
        }
        return workspace.resolve(name + Autosave.DRAFT_SUFFIX);
    }

    private static Path workingDraftTarget(Path file) {
        if (file == null) {
            return null;
        }
        String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
        if (fileName.endsWith(Autosave.DRAFT_SUFFIX)) {
            return file;
        }
        String stem = fileName.endsWith(".epub") || fileName.endsWith(".txt")
                ? fileName.substring(0, fileName.lastIndexOf('.'))
                : Autosave.stripDraftSuffix(fileName);
        Path parent = file.getParent();
        Path targetName = Path.of(stem + Autosave.DRAFT_SUFFIX);
        return parent == null ? targetName : parent.resolve(targetName);
    }
}
