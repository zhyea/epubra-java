package org.chobit.epubra.app.ui.support.validation;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.util.Hrefs;
import org.chobit.epubra.lib.validation.ValidationIssue;
import org.chobit.epubra.lib.validation.ValidationReport;

/**
 * 校验面板的纯逻辑：报告统计、状态栏文本、问题行里资源 href 的解析。
 *
 * <p>校验本身由 {@code EpubValidator} 完成；本类只承担把 {@link ValidationReport}
 * 翻译成面板/状态栏需要的小段文本与坐标。
 */
public final class ValidationOps {

    private ValidationOps() {
    }

    /**
     * 在问题里拿到的 {@code resourceHref} 可能是容器内绝对路径，也可能是相对于
     * OPF 内容目录的相对路径；两种都试一遍。
     *
     * @return 命中返回资源，否则 null
     */
    public static Resource resolveIssueResource(Book book, String href) {
        if (href == null || href.isBlank()) {
            return null;
        }
        Resource direct = book.resources().getByHref(href);
        if (direct != null) {
            return direct;
        }
        return book.resources().getByHref(Hrefs.resolve(book.contentDirectory(), href));
    }

    /**
     * 状态栏上需要给用户看的「错误 / 警告」统计。
     *
     * @return {@code "无问题"}（报告为空）、{@code "X 个错误 · Y 个警告"}（否则）
     */
    public static String statusText(ValidationReport report) {
        if (report == null || report.isEmpty()) {
            return "无问题";
        }
        return report.errorCount() + " 个错误 · " + report.warningCount() + " 个警告";
    }

    /**
     * 状态栏的「问题面板是否含容器级规则」摘要。
     *
     * @return {@code "含容器级规则"}（保存后做容器级校验）/ {@code "仅内存校验，保存后可得容器级结果"}
     */
    public static String scopeSummary(ValidationReport report) {
        if (report == null || report.isEmpty()) {
            return "";
        }
        return report.containerChecked() ? "含容器级规则" : "仅内存校验，保存后可得容器级结果";
    }

    /**
     * 在问题列表里筛出与某资源相关的条目 — 用于「双击目录节点时高亮属于它的所有问题」。
     */
    public static int countIssuesAffecting(ValidationReport report, String resourceHref) {
        if (report == null || resourceHref == null || resourceHref.isBlank()) {
            return 0;
        }
        int n = 0;
        for (ValidationIssue issue : report.issues()) {
            if (resourceHref.equals(issue.resourceHref())) {
                n++;
            }
        }
        return n;
    }
}
