package org.chobit.epubra.lib.validation;

import java.util.Objects;

/**
 * 一条校验结果。
 *
 * @param kind         对应的规则
 * @param severity     实际级别（通常与 {@link IssueKind#severity()} 相同，允许逐条覆盖）
 * @param message      已填充具体值的完整中文描述
 * @param resourceHref 可定位的资源在容器内的路径；整书级 / 容器级问题为 {@code null}
 * @param detail       技术细节（原始目标串、解析后的路径等），用于悬浮提示
 */
public record ValidationIssue(
        IssueKind kind,
        Severity severity,
        String message,
        String resourceHref,
        String detail
) {

    public ValidationIssue(IssueKind kind, String message) {
        this(kind, kind.severity(), message, null, null);
    }

    public ValidationIssue(IssueKind kind, String message, String resourceHref) {
        this(kind, kind.severity(), message, resourceHref, null);
    }

    public ValidationIssue(IssueKind kind, String message, String resourceHref, String detail) {
        this(kind, kind.severity(), message, resourceHref, detail);
    }

    public ValidationIssue(IssueKind kind, Severity severity, String message, String resourceHref, String detail) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.severity = severity == null ? kind.severity() : severity;
        this.message = message == null ? kind.template() : message;
        this.resourceHref = resourceHref;
        this.detail = detail;
    }

    /** 问题所在位置；整书级问题返回「整书」。 */
    public String location() {
        return resourceHref == null || resourceHref.isBlank() ? "整书" : resourceHref;
    }
}
