package org.chobit.epubra.app.support.context;

import org.chobit.epubra.app.EpubraLauncher;
import org.chobit.epubra.app.support.document.AutosaveConfig;
import org.chobit.epubra.app.support.document.BookHistory;
import org.chobit.epubra.app.support.platform.AppPaths;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.validation.ValidationReport;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * 跨控制器共享的应用状态。
 *
 * <p>主窗口的运行状态原本散落在 {@code MainController} 的 30+ 字段里，导致任何子控制器
 * 都必须拿到主控制器引用才能读写。重构后，{@code MainController} 与各子控制器共同持有
 * 同一个 {@code BookContext} 实例，按需读 / 写其中的字段。
 *
 * <p>字段命名沿用旧 MainController 中的命名，避免拆分后引入额外的翻译成本。
 *
 * <h2>归类</h2>
 * <ul>
 *   <li><b>文档</b>：{@link #book()}、{@link #currentFile()}、{@link #dirty()}、{@link #currentNode()}、{@link #loading()}</li>
 *   <li><b>撤销栈</b>：{@link #history()}、{@link #editCaptured()}、{@link #editStepPause()}</li>
 *   <li><b>校验</b>：{@link #lastReport()}</li>
 *   <li><b>字数缓存</b>：{@link #wordCounts()}</li>
 *   <li><b>阶段</b>：{@link #stage()}</li>
 * </ul>
 */
public final class BookContext {

    /**
     * 连续输入静默这么久即视为一个编辑步结束，之后的输入会计入新的一步。
     *
     * <p>改用 {@link java.time.Duration} 而非 JavaFX 的 {@code javafx.util.Duration}：
     * BookContext 是纯数据载体，不应触发 JavaFX Toolkit 初始化，
     * 否则单元测试里直接 {@code new BookContext()} 就会炸（Toolkit not initialized）。
     */
    public static final java.time.Duration EDIT_STEP_IDLE = java.time.Duration.ofMillis(600);

    // ---- 文档 ----
    private Book book;
    private Path currentFile;
    private ChapterNode currentNode;
    private boolean dirty;
    private boolean loading;

    // ---- 撤销 ----
    private final BookHistory history = new BookHistory();
    private boolean editCaptured;

    // ---- 校验 ----
    private ValidationReport lastReport = ValidationReport.EMPTY;

    // ---- 字数缓存 ----
    private final Map<Resource, Integer> wordCounts = new IdentityHashMap<>();

    // ---- 阶段 ----
    private Stage stage;

    // ---- 自动暂存配置（autosave.enabled / autosave.debounceSeconds / autosave.dir）----
    private AutosaveConfig autosaveConfig;

    // ---- 事件总线 ----
    private final AppEventBus bus = new AppEventBus();

    public BookContext() {
    }

    // ---- 文档 ----

    public Book book() {
        return book;
    }

    public void setBook(Book book) {
        this.book = book;
    }

    public Path currentFile() {
        return currentFile;
    }

    public void setCurrentFile(Path currentFile) {
        this.currentFile = currentFile;
    }

    public ChapterNode currentNode() {
        return currentNode;
    }

    public void setCurrentNode(ChapterNode currentNode) {
        this.currentNode = currentNode;
    }

    public boolean dirty() {
        return dirty;
    }

    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    public boolean loading() {
        return loading;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    // ---- 撤销 ----

    public BookHistory history() {
        return history;
    }

    public boolean editCaptured() {
        return editCaptured;
    }

    public void setEditCaptured(boolean editCaptured) {
        this.editCaptured = editCaptured;
    }

    /** 编辑步空闲窗口长度，供控制器层（UndoController 等）构造 PauseTransition 使用。 */
    public java.time.Duration editStepIdle() {
        return EDIT_STEP_IDLE;
    }

    // ---- 校验 ----

    public ValidationReport lastReport() {
        return lastReport;
    }

    public void setLastReport(ValidationReport lastReport) {
        this.lastReport = lastReport;
    }

    // ---- 字数缓存 ----

    public Map<Resource, Integer> wordCounts() {
        return wordCounts;
    }

    /** 清空字数缓存：换书 / 章节内容被程序化改写后调用。 */
    public void invalidateWordCounts() {
        wordCounts.clear();
    }

    // ---- 阶段 ----

    public Stage stage() {
        return stage;
    }

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    // ---- 自动暂存 ----

    public AutosaveConfig autosaveConfig() {
        if (autosaveConfig == null) {
            autosaveConfig = AutosaveConfig.read();
        }
        return autosaveConfig;
    }

    public void setAutosaveConfig(AutosaveConfig autosaveConfig) {
        this.autosaveConfig = autosaveConfig;
    }

    /**
     * 自动暂存目录：未保存的新书的草稿落盘位置。
     *
     * <p>优先级：
     * <ol>
     *   <li>{@link AutosaveConfig#dirOverride()} 非空 → 用其值</li>
     *   <li>否则用 {@link AppPaths#autosaveDir()}（默认 {@code <user.home>/.Epubra/autosave}，
     *       {@link EpubraLauncher} 启动时已把 {@code user.home} 重定向到 {@code ~/.Epubra/}）</li>
     * </ol>
     *
     * <p>目录不存在时懒创建。创建失败回退到 {@link AppPaths#autosaveDir()} 同名目录下的临时副本，
     * 再失败则用系统临时目录。永不抛异常——自动暂存是"尽力而为"的保护机制，不应让主流程崩溃。
     */
    public Path autosaveDir() {
        AutosaveConfig cfg = autosaveConfig();
        String override = cfg == null ? null : cfg.dirOverride();
        Path base = (override == null || override.isBlank())
                ? AppPaths.autosaveDir()
                : Path.of(override);
        try {
            Files.createDirectories(base);
            return base;
        } catch (IOException first) {
            // fallback：~/.Epubra/autosave 副本（同名加 .tmp 后缀），再失败走系统临时目录
            try {
                Path fallback = Path.of(System.getProperty("java.io.tmpdir", "."),
                        AppPaths.AUTOSAVE_SUBDIR);
                Files.createDirectories(fallback);
                return fallback;
            } catch (IOException second) {
                Path tmp = Path.of(System.getProperty("java.io.tmpdir", "."), "epubra-autosave");
                try {
                    Files.createDirectories(tmp);
                } catch (IOException ignored) {
                }
                return tmp;
            }
        }
    }

    // ---- 事件总线 ----

    public AppEventBus bus() {
        return bus;
    }

    // ---- 一次性重置：换书场景 ----

    /**
     * 换书前调用：清掉撤销栈、字数缓存、当前节点、校验结果。
     * 不动 {@link #stage()}（窗口跨书复用）。
     */
    public void resetForNewBook() {
        history.reset();
        editCaptured = false;
        wordCounts.clear();
        currentNode = null;
        lastReport = ValidationReport.EMPTY;
        dirty = false;
    }
}
