package org.chobit.epubra.app.support.validation;

import org.chobit.epubra.lib.validation.IssueGroup;
import org.chobit.epubra.lib.validation.Severity;
import org.chobit.epubra.lib.validation.ValidationIssue;
import org.chobit.epubra.lib.validation.ValidationReport;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 校验结果的展示逻辑：中文标签、摘要文案、状态栏文案与列表过滤。
 *
 * <p>刻意与 JavaFX 控件无关，放在 {@code support} 包下，没有 JavaFX 运行时也能跑单测。
 */
public final class ValidationTexts {

    /** 容器级结果滞后时的状态栏提示。 */
    private static final String STALE_HINT = "（容器级结果基于磁盘上的文件，未保存的修改未计入）";

    private ValidationTexts() {
    }

    /** 级别中文标签；未知级别回退为「警告」。 */
    public static String severityLabel(Severity severity) {
        if (severity == null) {
            return Severity.WARNING.label();
        }
        return severity.label();
    }

    /** 分组中文标签；未知分组回退为「其他」。 */
    public static String groupLabel(IssueGroup group) {
        return group == null ? "其他" : group.label();
    }

    /** 报告摘要：空报告为「未发现问题」，否则为「N 个错误 · M 个警告」。 */
    public static String summary(ValidationReport report) {
        if (report == null || report.isEmpty()) {
            return "未发现问题";
        }
        if (report.warningCount() == 0) {
            return report.errorCount() + " 个错误";
        }
        if (report.errorCount() == 0) {
            return report.warningCount() + " 个警告";
        }
        return report.errorCount() + " 个错误 · " + report.warningCount() + " 个警告";
    }

    /**
     * 状态栏文案。
     *
     * @param containerStale 容器级规则确实跑过、且当前有未保存修改时为 true，追加提示避免静默误报
     */
    public static String statusText(ValidationReport report, boolean containerStale) {
        String text = "校验完成：" + summary(report);
        return containerStale ? text + STALE_HINT : text;
    }

    /**
     * 按关键字与「只看错误」过滤问题列表。
     *
     * <p>关键字为空白时不过滤；匹配范围为说明 + 位置 + 技术细节，忽略大小写。
     */
    public static List<ValidationIssue> filter(List<ValidationIssue> issues, String keyword, boolean onlyErrors) {
        if (issues == null || issues.isEmpty()) {
            return List.of();
        }
        String needle = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<ValidationIssue> filtered = new ArrayList<>();
        for (ValidationIssue issue : issues) {
            if (onlyErrors && issue.severity() != Severity.ERROR) {
                continue;
            }
            if (needle.isEmpty() || matches(issue, needle)) {
                filtered.add(issue);
            }
        }
        return filtered;
    }

    private static boolean matches(ValidationIssue issue, String needle) {
        return contains(issue.message(), needle)
                || contains(issue.location(), needle)
                || contains(issue.detail(), needle);
    }

    private static boolean contains(String text, String needle) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(needle);
    }
}
