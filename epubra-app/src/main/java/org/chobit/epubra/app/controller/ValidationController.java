package org.chobit.epubra.app.controller;

import org.chobit.epubra.app.support.AsyncTasks;
import org.chobit.epubra.app.support.BookContext;
import org.chobit.epubra.app.support.TextSearch;
import org.chobit.epubra.app.support.AppEventBus;
import org.chobit.epubra.app.support.ValidationTexts;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;
import org.chobit.epubra.lib.validation.EpubValidator;
import org.chobit.epubra.lib.validation.ValidationIssue;
import org.chobit.epubra.lib.validation.ValidationReport;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 校验面板控制器——维护 {@code issueTable} / 3 个计数标签与定位跳转，
 * 跑校验并把结果显示到面板上。
 *
 * <p>作为 {@code problems-panel.fxml} 的 {@code fx:controller} 由 FXML 实例化：面板内
 * 的表格与标签经 {@code @FXML} 注入；编辑区节点、目录树 / 侧栏控制器引用与回调在父
 * 控制器 {@code initialize()} 阶段通过 {@link #bind} 注入。本类不得定义 {@code initialize()}。
 *
 * <p>校验是只读操作：跑校验前先调 {@code commitPendingEdits} 把当前编辑同步回
 * {@link Book}，但<b>不</b>触发任何历史快照。
 * 双击问题行可定位到正文：{@link #onRowActivated(ValidationIssueRow)}
 * 切到内容页签、选中目录节点、并尽量把光标带到问题锚点。
 */
public class ValidationController {

    @FXML
    private TableView<ValidationIssueRow> issueTable;
    @FXML
    private Label problemErrorLabel;
    @FXML
    private Label problemWarningLabel;
    @FXML
    private Label problemSummaryLabel;

    private BookContext ctx;
    private EpubValidator validator;
    private TabPane editorTabs;
    private TextArea contentArea;
    private TocController tocController;
    private SidebarController sidebarController;
    private Runnable commitPendingEdits;
    private Consumer<String> setStatus;
    private AsyncTasks.ProgressController progress;

    /** FXML 加载后由父控制器注入运行时依赖；必须在任何 onAction 触发前完成。 */
    public void bind(BookContext ctx, EpubValidator validator,
                     TabPane editorTabs, TextArea contentArea,
                     TocController tocController, SidebarController sidebarController,
                     Runnable commitPendingEdits, Consumer<String> setStatus,
                     AsyncTasks.ProgressController progress) {
        this.ctx = ctx;
        this.validator = validator;
        this.editorTabs = editorTabs;
        this.contentArea = contentArea;
        this.tocController = tocController;
        this.sidebarController = sidebarController;
        this.commitPendingEdits = commitPendingEdits;
        this.setStatus = setStatus;
        this.progress = progress;
    }

    /** 面板头「关闭」按钮：收起底部面板并还原活动栏选中态。 */
    @FXML
    public void onClosePanel() {
        if (sidebarController != null) {
            sidebarController.hideProblems();
        }
    }

    /** 问题表格初始化：列宽自适应、悬浮提示显示规则编号与技术细节、双击触发定位。 */
    public void setupTable() {
        if (issueTable == null) {
            return;
        }
        issueTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        issueTable.setRowFactory(table -> {
            TableRow<ValidationIssueRow> row = new TableRow<>() {
                @Override
                protected void updateItem(ValidationIssueRow item, boolean empty) {
                    super.updateItem(item, empty);
                    setTooltip(item == null || empty ? null : new Tooltip(item.tooltipText()));
                }
            };
            row.setOnMouseClicked((MouseEvent event) -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    onRowActivated(row.getItem());
                }
            });
            return row;
        });
    }

    /** 跑一次校验，刷新问题面板。读操作：不打快照、不脏标记。 */
    public void run() {
        if (ctx.book() == null) {
            return;
        }
        commitPendingEdits.run();
        // 快照 book 与容器路径——后台线程只读这些引用，避免与 FX 线程换书时漂移。
        // containerFile 可能因「刚换了书但 currentFile 未更新」而指向旧文件，但
        // validator 内部对不存在 / null 都有降级处理，行为有界。
        Book bookAtStart = ctx.book();
        Path container = ctx.currentFile() != null && Files.isRegularFile(ctx.currentFile())
                ? ctx.currentFile()
                : null;
        AsyncTasks.runIo(
                "正在校验 " + (container != null ? container.getFileName() : "内存中的书籍"),
                () -> validator.validate(bookAtStart, container),
                progress != null ? progress : AsyncTasks.NOOP_PROGRESS,
                this::applyReport,
                err -> setStatus.accept("校验失败：" + err.getMessage())
        );
    }

    /**
     * FX 线程回调：把校验报告灌进面板。拆成独立方法便于在测试里直接调用
     * （跳过异步等待，直接验证面板渲染逻辑）。
     */
    public void applyReport(ValidationReport report) {
        ctx.setLastReport(report);
        issueTable.getItems().setAll(report.issues().stream().map(ValidationIssueRow::new).toList());
        updateProblemHeader();
        if (sidebarController != null) {
            sidebarController.showBottomPanelOnly(true);
        }
        setStatus.accept(ValidationTexts.statusText(report, report.containerChecked() && ctx.dirty()));
        ctx.bus().publish(new AppEventBus.ValidationCompletedEvent(report));
    }

    /**
     * 清空上一次的校验结果。
     *
     * <p>报告只对产生它的那一次校验有效：新建、打开与撤销 / 重做之后，书已经换了，
     * 面板与状态栏若继续挂着旧数字就是误导。
     */
    public void clear() {
        ctx.setLastReport(ValidationReport.EMPTY);
        if (issueTable != null) {
            issueTable.getItems().clear();
        }
        updateProblemHeader();
    }

    /** 刷新面板头的错误 / 警告计数、摘要。状态栏的汇总由 MainController 同步刷新。 */
    public void updateProblemHeader() {
        if (problemErrorLabel != null) {
            problemErrorLabel.setText("错误 " + ctx.lastReport().errorCount());
        }
        if (problemWarningLabel != null) {
            problemWarningLabel.setText("警告 " + ctx.lastReport().warningCount());
        }
        if (problemSummaryLabel != null) {
            problemSummaryLabel.setText(problemHint());
        }
    }

    /** 面板头右侧的一句说明，讲清这次结果覆盖了哪些规则。 */
    private String problemHint() {
        if (ctx.lastReport().isEmpty()) {
            return "未发现问题";
        }
        return ctx.lastReport().containerChecked() ? "含容器级规则" : "仅内存校验，保存后可得容器级结果";
    }

    /** 双击问题行：切到内容页签、在目录树里选中对应章节，并尽量把光标带到出问题的位置。 */
    public void onRowActivated(ValidationIssueRow row) {
        if (row == null) {
            return;
        }
        ValidationIssue issue = row.issue();
        Resource target = resolveIssueResource(issue.resourceHref());
        if (target == null) {
            setStatus.accept("该问题属于整书级别，没有可定位的章节");
            return;
        }
        editorTabs.getSelectionModel().selectFirst();
        if (tocController != null && !tocController.selectResource(target)) {
            setStatus.accept("已选中 " + target.fileName() + "（该资源不在目录中）");
            return;
        }
        highlightIssueAnchor(row);
    }

    /** 问题里的 href 多数是容器内路径，少数是相对内容目录的写法，两种都试一遍。 */
    private Resource resolveIssueResource(String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        Resource direct = ctx.book().resources().getByHref(href);
        if (direct != null) {
            return direct;
        }
        return ctx.book().resources().getByHref(Hrefs.resolve(ctx.book().contentDirectory(), href));
    }

    /** 在正文中选中出问题的位置：锚点 id 优先，其次引用原文串，再退化为文件名。 */
    private void highlightIssueAnchor(ValidationIssueRow row) {
        String anchor = row.anchor();
        if (anchor.isEmpty() || contentArea.isDisabled()) {
            contentArea.positionCaret(0);
            return;
        }
        String text = contentArea.getText();
        int index = row.anchorIsFragment() ? TextSearch.indexOfIdAttribute(text, anchor) : -1;
        if (index < 0) {
            index = TextSearch.indexOf(text, anchor, 0, true);
        }
        if (index < 0) {
            int slash = anchor.lastIndexOf('/');
            if (slash >= 0 && slash + 1 < anchor.length()) {
                index = TextSearch.indexOf(text, anchor.substring(slash + 1), 0, true);
            }
        }
        if (index < 0) {
            contentArea.positionCaret(0);
            setStatus.accept("已定位到章节，但正文中没找到该引用的位置");
            return;
        }
        contentArea.requestFocus();
        contentArea.selectRange(index, index + anchor.length());
        setStatus.accept("已定位到问题所在位置");
    }
}
