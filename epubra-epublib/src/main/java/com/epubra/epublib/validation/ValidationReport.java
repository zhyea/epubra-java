package com.epubra.epublib.validation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 一次校验的结果集：构造时即完成排序，任何入口拿到的顺序都一致。
 *
 * <p>排序规则（全局唯一）：级别（错误优先）→ 分组 → 资源路径 → 规则名 → 文案。
 */
public final class ValidationReport {

    /** 空报告，用于界面初始化与「清空结果」。 */
    public static final ValidationReport EMPTY = new ValidationReport(List.of(), false);

    private static final Comparator<ValidationIssue> ORDER =
            Comparator.comparing((ValidationIssue issue) -> issue.severity())
                    .thenComparing((ValidationIssue issue) -> issue.kind().group())
                    .thenComparing((ValidationIssue issue) -> issue.resourceHref() == null ? "" : issue.resourceHref())
                    .thenComparing((ValidationIssue issue) -> issue.kind().name())
                    .thenComparing((ValidationIssue issue) -> issue.message() == null ? "" : issue.message());

    private final List<ValidationIssue> issues;
    private final boolean containerChecked;
    private final List<ValidationIssue> errors;
    private final List<ValidationIssue> warnings;

    public ValidationReport(List<ValidationIssue> issues, boolean containerChecked) {
        List<ValidationIssue> sorted = new ArrayList<>(issues == null ? List.of() : issues);
        sorted.sort(ORDER);
        this.issues = List.copyOf(sorted);
        this.containerChecked = containerChecked;

        List<ValidationIssue> errorList = new ArrayList<>();
        List<ValidationIssue> warningList = new ArrayList<>();
        for (ValidationIssue issue : this.issues) {
            if (issue.severity() == Severity.ERROR) {
                errorList.add(issue);
            } else {
                warningList.add(issue);
            }
        }
        this.errors = List.copyOf(errorList);
        this.warnings = List.copyOf(warningList);
    }

    /** 全部问题，不可变且已排序。 */
    public List<ValidationIssue> issues() {
        return issues;
    }

    public List<ValidationIssue> errors() {
        return errors;
    }

    public List<ValidationIssue> warnings() {
        return warnings;
    }

    public int errorCount() {
        return errors.size();
    }

    public int warningCount() {
        return warnings.size();
    }

    public boolean isEmpty() {
        return issues.isEmpty();
    }

    /** 是否跑过容器级规则（需要有真实 .epub 文件）。 */
    public boolean containerChecked() {
        return containerChecked;
    }

    /** 一句话摘要，例如 {@code "未发现问题"} / {@code "3 个错误 · 5 个警告"}。 */
    public String summary() {
        if (issues.isEmpty()) {
            return "未发现问题";
        }
        if (warningCount() == 0) {
            return errorCount() + " 个错误";
        }
        if (errorCount() == 0) {
            return warningCount() + " 个警告";
        }
        return errorCount() + " 个错误 · " + warningCount() + " 个警告";
    }

    @Override
    public String toString() {
        return "ValidationReport{" + summary() + ", containerChecked=" + containerChecked + '}';
    }
}
