package org.chobit.epubra.app.activities;

import javafx.animation.PauseTransition;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.editor.TextSearch;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.app.ui.model.ChapterNode;
import org.chobit.epubra.lib.domain.Resource;

import java.util.function.Supplier;

/**
 * 状态栏编排：状态文案 / 长操作进度 / 章节与字数 / 校验计数 / 窗口标题。
 *
 * <p>从 {@code MainController} 拆出——这一组方法只读写状态栏控件与 {@link BookContext}，
 * 与文档编辑流程无耦合，独立出来后 {@code MainController} 只保留一行委派。
 *
 * <p>{@code stage} 与当前章节都是<b>晚绑定</b>的：stage 由 {@code EpubraApp} 在 FXML
 * 加载后才注入，当前章节由 {@code TocController} 持有，故这里一律用 {@link Supplier} 取。
 */
public class StatusCoordinator {

    private final BookContext ctx;
    private final TextArea contentArea;
    private final Supplier<ChapterNode> currentChapter;
    private final Supplier<Stage> stage;

    private final Label statusLabel;
    private final ProgressBar statusProgressBar;
    private final Label statusProgressLabel;
    private final Region statusProgressDivider;
    private final Label errorStatusLabel;
    private final Region errorStatusDivider;
    private final Label warningStatusLabel;
    private final Region warningStatusDivider;
    private final Label chapterStatusLabel;
    private final Label wordStatusLabel;
    private final Label chapterWordStatusLabel;
    private final MenuItem undoItem;
    private final MenuItem redoItem;

    public StatusCoordinator(BookContext ctx, TextArea contentArea,
                             Supplier<ChapterNode> currentChapter, Supplier<Stage> stage,
                             Label statusLabel,
                             ProgressBar statusProgressBar, Label statusProgressLabel, Region statusProgressDivider,
                             Label errorStatusLabel, Region errorStatusDivider,
                             Label warningStatusLabel, Region warningStatusDivider,
                             Label chapterStatusLabel, Label wordStatusLabel, Label chapterWordStatusLabel,
                             MenuItem undoItem, MenuItem redoItem) {
        this.ctx = ctx;
        this.contentArea = contentArea;
        this.currentChapter = currentChapter;
        this.stage = stage;
        this.statusLabel = statusLabel;
        this.statusProgressBar = statusProgressBar;
        this.statusProgressLabel = statusProgressLabel;
        this.statusProgressDivider = statusProgressDivider;
        this.errorStatusLabel = errorStatusLabel;
        this.errorStatusDivider = errorStatusDivider;
        this.warningStatusLabel = warningStatusLabel;
        this.warningStatusDivider = warningStatusDivider;
        this.chapterStatusLabel = chapterStatusLabel;
        this.wordStatusLabel = wordStatusLabel;
        this.chapterWordStatusLabel = chapterWordStatusLabel;
        this.undoItem = undoItem;
        this.redoItem = redoItem;
    }

    public void set(String message) {
        statusLabel.setText(message);
    }

    /**
     * 关键操作反馈：套 {@code .status-flash} 强调色，1.2s 后自动清除。
     */
    public void flash(String message) {
        set(message);
        if (statusLabel == null) {
            return;
        }
        if (!statusLabel.getStyleClass().contains("status-flash")) {
            statusLabel.getStyleClass().add("status-flash");
        }
        PauseTransition flash = new PauseTransition(Duration.seconds(1.2));
        flash.setOnFinished(e -> statusLabel.getStyleClass().remove("status-flash"));
        flash.play();
    }

    /** 全文刷新：章节数 / 字数 / 本章字数 / 校验计数 / 撤销可用性 / 窗口标题。 */
    public void refresh() {
        if (ctx.book() == null) {
            chapterStatusLabel.setText("章节 —");
            wordStatusLabel.setText("字数 —");
            setChapterWordStatus(null);
            updateHistoryControls();
            return;
        }
        chapterStatusLabel.setText("章节 " + ctx.book().spineResources().size());
        wordStatusLabel.setText("字数 " + wordCount());
        setChapterWordStatus(currentChapter.get());
        updateIssueCounters();
        updateHistoryControls();
        updateTitle();
    }

    public void updateHistoryControls() {
        boolean canUndo = ctx.book() != null && ctx.history().canUndo();
        boolean canRedo = ctx.book() != null && ctx.history().canRedo();
        if (undoItem != null) {
            undoItem.setDisable(!canUndo);
        }
        if (redoItem != null) {
            redoItem.setDisable(!canRedo);
        }
    }

    public void updateTitle() {
        Stage s = stage.get();
        if (s == null) {
            return;
        }
        String name = ctx.currentFile() == null ? "新书籍" : ctx.currentFile().getFileName().toString();
        // dirty 标记用 ● / ○（U+25CF / U+25CB）放在最前——比藏在末尾的 * 显著得多
        String marker = ctx.dirty() ? "\u25CF " : "\u25CB ";
        s.setTitle(marker + "Epubra" + " - " + name);
    }

    /**
     * 当前章节字数。未选中章节时显示「本章 —」而不是 0——0 看起来像「这章是空的」，
     * 与「还没选章节」是两回事。
     */
    private void setChapterWordStatus(ChapterNode node) {
        if (chapterWordStatusLabel == null) {
            return;
        }
        if (node == null || node.resource() == null) {
            chapterWordStatusLabel.setText("本章 —");
            return;
        }
        // 编辑区可用时以它的实时内容为准——用户敲进去还没写回的字符也要算进去
        String text = contentArea != null && !contentArea.isDisabled()
                ? contentArea.getText()
                : node.resource().asString();
        chapterWordStatusLabel.setText("本章 " + TextSearch.plainTextLength(text) + " 字");
    }

    /**
     * 状态栏的错误 / 警告计数，取自最近一次校验结果。
     *
     * <p>零值不显示——避免「错误 0 / 警告 0」这种恒常噪音。注意必须连同标签后面那条
     * 分隔竖线一起隐藏，只清文本会留下孤立竖线。
     */
    private void updateIssueCounters() {
        int err = ctx.lastReport().errorCount();
        int warn = ctx.lastReport().warningCount();
        if (errorStatusLabel != null) {
            errorStatusLabel.setText(err > 0 ? "错误 " + err : "");
        }
        if (warningStatusLabel != null) {
            warningStatusLabel.setText(warn > 0 ? "警告 " + warn : "");
        }
        setVisibleManaged(errorStatusDivider, err > 0);
        setVisibleManaged(warningStatusDivider, warn > 0);
    }

    /**
     * 全书正文字数：各章节 XHTML 剥离标签后的非空白字符数之和。
     *
     * <p>状态栏在每次击键后都会刷新，因此逐章统计的结果按资源缓存起来，只有当前正在编辑的
     * 那一章实时统计（编辑器里尚未写回的输入也要计入）。缓存由
     * {@link BookContext#invalidateWordCounts()} 在内容被程序化改写或换书时整体失效。
     */
    private int wordCount() {
        if (ctx.book() == null) {
            return 0;
        }
        ChapterNode chapterNode = currentChapter.get();
        Resource current = chapterNode == null ? null : chapterNode.resource();
        int total = 0;
        for (Resource chapter : ctx.book().spineResources()) {
            if (chapter == current && !contentArea.isDisabled()) {
                total += TextSearch.plainTextLength(contentArea.getText());
                continue;
            }
            total += ctx.wordCounts().computeIfAbsent(chapter, resource -> TextSearch.plainTextLength(resource.asString()));
        }
        return total;
    }

    /**
     * 长操作进度反馈器：包装状态栏的 ProgressBar + 标签 + 分隔竖线，让
     * {@link AsyncTasks#runIo} 在工作开始时把它们显示出来、结束时隐藏。
     *
     * <p>所有回调都在 FX 线程触发（{@link AsyncTasks} 已用 {@code Platform.runLater}
     * 包好），直接读 / 写控件属性即可，不需要再次切线程。
     *
     * <p>这里没有把进度条做成「跨任务互斥」——同一窗口内不会同时跑两个长操作，但
     * 万一有，新任务调 {@code begin} 会覆盖旧任务留下的标题，done 会把 UI 隐藏。
     */
    public AsyncTasks.ProgressController progressSink() {
        return new AsyncTasks.ProgressController() {
            @Override
            public void begin(String title) {
                if (statusProgressBar == null || statusProgressLabel == null) {
                    return;
                }
                statusProgressLabel.setText(title);
                statusProgressBar.setProgress(-1); // indeterminate
                setVisibleManaged(statusProgressBar, true);
                setVisibleManaged(statusProgressLabel, true);
                setVisibleManaged(statusProgressDivider, true);
            }

            @Override
            public void update(double fraction) {
                if (statusProgressBar == null) {
                    return;
                }
                if (fraction < 0) {
                    statusProgressBar.setProgress(-1);
                } else {
                    statusProgressBar.setProgress(clamp01(fraction));
                }
            }

            @Override
            public void done() {
                if (statusProgressBar == null || statusProgressLabel == null) {
                    return;
                }
                setVisibleManaged(statusProgressBar, false);
                setVisibleManaged(statusProgressLabel, false);
                setVisibleManaged(statusProgressDivider, false);
                statusProgressBar.setProgress(0);
                statusProgressLabel.setText("");
            }
        };
    }

    /**
     * 错误弹窗。参数是 {@link Throwable} 而非 {@link Exception}：
     * {@code AsyncTasks.runIo} 的 onError 拿到的是 {@code Throwable}
     * （{@code onSuccess} 回调自身抛出的 {@link Error} 也会被 routed 进来），
     * 签名放宽后调用方直接透传，不需要强转——强转 {@code (Exception)} 在
     * Error 场景会抛 ClassCastException，把真正的故障吞掉。
     */
    public void showError(String title, String message, Throwable e) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(message);
        alert.setContentText(e == null ? "" : e.getMessage());
        Stage s = stage.get();
        if (s != null) {
            alert.initOwner(s);
        }
        alert.showAndWait();
    }

    /** 状态栏分区显隐助手：visible 与 managed 必须同步，否则隐藏后仍占布局间距。 */
    private static void setVisibleManaged(Region node, boolean visible) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(visible);
    }

    private static double clamp01(double v) {
        if (Double.isNaN(v)) {
            return 0;
        }
        if (v < 0) {
            return 0;
        }
        return Math.min(1, v);
    }
}
