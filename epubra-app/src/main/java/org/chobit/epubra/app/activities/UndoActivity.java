package org.chobit.epubra.app.activities;

import org.chobit.epubra.app.ui.support.context.BookContext;
import org.chobit.epubra.app.support.document.BookHistory;
import org.chobit.epubra.app.ui.support.context.AppEventBus;
import org.chobit.epubra.lib.domain.Book;
import javafx.animation.PauseTransition;
import javafx.util.Duration;

/**
 * 撤销 / 重做活动：把 MainController 里散落的撤销栈交互抽出来。
 *
 * <p>本类依赖 JavaFX（持有 {@link PauseTransition} 用于「600ms 静默后结束编辑步」）。
 * 由于它只在 JavaFX 应用启动后才被构造，单元测试需要 fake 一个 BookContext + Runnable
 * 即可，无需 Toolkit。
 *
 * <p>{@link #restore} 完成后发 {@code BookRestoredEvent}；所有需要「撤销后重新渲染」的
 * 子控制器应订阅它替代原本手工调用的 {@code refreshAll()}。
 */
public class UndoActivity {

    /** 撤销栈内无操作时给用户的反馈。 */
    @FunctionalInterface
    public interface StatusSink {
        void setStatus(String message);
    }

    /** 撤销后清空旧的校验结果（报告只对产生它的状态有效）。 */
    @FunctionalInterface
    public interface ValidationClearer {
        void run();
    }

    private final BookContext ctx;
    private final StatusSink status;
    private final ValidationClearer validationClearer;
    private final PauseTransition editStepPause;
    private Runnable flushCurrentChapter;
    private Runnable flushMetadata;

    public UndoActivity(BookContext ctx, StatusSink status) {
        this(ctx, status, () -> {});
    }

    public UndoActivity(BookContext ctx, StatusSink status,
                        ValidationClearer validationClearer) {
        this.ctx = ctx;
        this.status = status;
        this.validationClearer = validationClearer;
        this.editStepPause = new PauseTransition(Duration.millis(ctx.editStepIdle().toMillis()));
        this.editStepPause.setOnFinished(event -> ctx.setEditCaptured(false));
    }

    /**
     * 供单元测试用：不创建 PauseTransition（JavaFX Toolkit 不可用），所有计时相关的
     * 编辑步合并逻辑退化为「立即重置 editCaptured」。这是教科书式的时间依赖解耦。
     */
    static UndoActivity forTesting(BookContext ctx, StatusSink status,
                                   ValidationClearer validationClearer) {
        return new UndoActivity(ctx, status, validationClearer, true);
    }

    private UndoActivity(BookContext ctx, StatusSink status,
                         ValidationClearer validationClearer, boolean headless) {
        this.ctx = ctx;
        this.status = status;
        this.validationClearer = validationClearer;
        if (headless) {
            this.editStepPause = null; // 见 playEditStepTimer
        } else {
            this.editStepPause = new PauseTransition(Duration.millis(ctx.editStepIdle().toMillis()));
            this.editStepPause.setOnFinished(event -> ctx.setEditCaptured(false));
        }
    }

    /** FXML onUndo 入口。 */
    public void undo() {
        if (!ctx.history().canUndo()) {
            status.setStatus("没有可撤销的操作");
            return;
        }
        commitPendingEdits();
        BookHistory.Snapshot snapshot = ctx.history().undo(ctx.book(), ctx.dirty());
        if (snapshot == null) {
            return;
        }
        restore(snapshot, "已撤销");
    }

    /** FXML onRedo 入口。 */
    public void redo() {
        if (!ctx.history().canRedo()) {
            status.setStatus("没有可重做的操作");
            return;
        }
        commitPendingEdits();
        BookHistory.Snapshot snapshot = ctx.history().redo(ctx.book(), ctx.dirty());
        if (snapshot == null) {
            return;
        }
        restore(snapshot, "已重做");
    }

    /**
     * 记录一次变更「之前」的状态，并结束当前的输入编辑步。
     *
     * <p>必须在真正修改 {@link Book} 之前调用：它会先把正文与元数据写回，再拍快照，
     * 之后本次操作引起的界面文本变更不再重复计入历史。
     */
    public void beginChange() {
        commitPendingEdits();
        ctx.history().record(ctx.book(), ctx.dirty());
        ctx.invalidateWordCounts();
        ctx.setEditCaptured(true);
        playEditStepTimer();
    }

    /**
     * 记录一次「界面文本即将写回书籍之前」的状态。
     *
     * <p>{@link #beginChange} 的顺序是先写回、再快照，适用于「先改结构」的操作；
     * 元数据这类「界面文本本身就是变更内容」的操作必须反过来，否则快照里已经是新值。
     */
    public void recordBeforeChange() {
        ctx.history().record(ctx.book(), ctx.dirty());
        ctx.setEditCaptured(true);
        playEditStepTimer();
    }

    public void commitPendingEdits() {
        if (flushCurrentChapter != null) {
            flushCurrentChapter.run();
        }
        if (flushMetadata != null) {
            flushMetadata.run();
        }
    }

    /** 注入 flush 回调，使 {@link #beginChange()} / {@link #commitPendingEdits()} 可用。 */
    public void installFlushCallbacks(Runnable flushCurrentChapter, Runnable flushMetadata) {
        this.flushCurrentChapter = flushCurrentChapter;
        this.flushMetadata = flushMetadata;
    }

    /**
     * 把一个快照还原成当前书。
     *
     * <p>{@link BookHistory} 已重新构造 Book 实例，原先引用都不再有效，必须把
     * {@code ctx.book} / {@code ctx.currentNode} / {@code ctx.dirty} 一起更新。
     */
    public void restore(BookHistory.Snapshot snapshot, String message) {
        ctx.setBook(snapshot.book());
        ctx.book().setSource(ctx.currentFile());
        ctx.setCurrentNode(null);
        ctx.setDirty(snapshot.dirty());
        ctx.setEditCaptured(false);
        ctx.bus().publish(new AppEventBus.BookRestoredEvent());
        validationClearer.run();
        status.setStatus(message);
    }

    /**
     * 正文 / 元数据输入事件：一段连续输入只在第一次击键时记录一次快照。
     */
    public void onTextInput() {
        if (!ctx.editCaptured()) {
            ctx.history().record(ctx.book(), ctx.dirty());
            ctx.setEditCaptured(true);
        }
        playEditStepTimer();
    }

    /** 在 headless 测试构造下 PauseTransition 为 null，此处退化为 no-op。 */
    private void playEditStepTimer() {
        if (editStepPause != null) {
            editStepPause.playFromStart();
        }
    }

    // ---- 单元测试 / 未来编程入口 ----

    /** 仅供单元测试与子控制器复用：history 是否能撤销。 */
    public boolean canUndo() {
        return ctx.history().canUndo();
    }

    public boolean canRedo() {
        return ctx.history().canRedo();
    }

    /** 当前书的引用（直接转发 ctx）。 */
    public Book book() {
        return ctx.book();
    }
}
