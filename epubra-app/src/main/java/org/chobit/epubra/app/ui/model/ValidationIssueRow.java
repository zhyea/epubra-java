package org.chobit.epubra.app.ui.model;

import org.chobit.epubra.app.support.validation.ValidationTexts;
import org.chobit.epubra.lib.validation.Severity;
import org.chobit.epubra.lib.validation.ValidationIssue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 校验问题表的行视图模型（供 JavaFX {@code PropertyValueFactory} 按 Bean 属性取值）。
 *
 * <p>四列分别为：级别 / 分组 / 说明 / 位置，对应 {@code getSeverity()} / {@code getGroup()} /
 * {@code getMessage()} / {@code getLocation()}。
 *
 * <p>与界面无关的部分（{@code detail} 解析 → 双击定位锚点）刻意保持纯逻辑，
 * 不 import 任何 JavaFX 类型，因此可以在无 JavaFX 环境下做单元测试。
 */
public class ValidationIssueRow {

    /** 按优先级尝试的锚点来源：锚点最准，其次为引用原文串。 */
    private static final String[] ANCHOR_KEYS = {"fragment", "target", "resolved", "href"};

    private final ValidationIssue issue;
    private final Map<String, String> detailFields;

    public ValidationIssueRow(ValidationIssue issue) {
        this.issue = Objects.requireNonNull(issue, "issue");
        this.detailFields = parseDetail(issue.detail());
    }

    /** 级别中文标签：「错误」/「警告」。 */
    public String getSeverity() {
        return ValidationTexts.severityLabel(issue.severity());
    }

    /** 分组中文标签：容器 / OPF / 阅读顺序 / 目录 / 资源引用 / 元数据。 */
    public String getGroup() {
        return ValidationTexts.groupLabel(issue.kind().group());
    }

    public String getMessage() {
        return issue.message() == null ? "" : issue.message();
    }

    /** 问题所在位置；整书级问题为「整书」。 */
    public String getLocation() {
        return issue.location();
    }

    /** 规则编号，例如 {@code "E02"}。 */
    public String getCode() {
        return issue.kind().code();
    }

    /** 技术细节原文；无细节时为空串，便于直接拼进提示。 */
    public String getDetail() {
        return issue.detail() == null ? "" : issue.detail();
    }

    public boolean isError() {
        return issue.severity() == Severity.ERROR;
    }

    /** 悬浮提示：规则编号 + 说明 + 技术细节。 */
    public String tooltipText() {
        String head = getCode() + "  " + getMessage();
        String detail = getDetail();
        return detail.isEmpty() ? head : head + "\n" + detail;
    }

    /**
     * 双击定位用的锚点，取不到时返回空串。
     *
     * <p>优先取 {@code fragment}（文档内 {@code id}），其次取引用原文串 {@code target} /
     * {@code resolved} / {@code href} 并去掉 {@code #} 及其后的片段部分——带片段的整串
     * 在 XHTML 里搜不到，去掉后至少能定位到 {@code src="..."} 这段引用。
     */
    public String anchor() {
        for (String key : ANCHOR_KEYS) {
            String value = detailFields.get(key);
            if (value != null && !value.isBlank()) {
                return stripFragment(value.trim());
            }
        }
        return "";
    }

    /** 锚点是否为文档内 {@code id}：是则正文中应搜 {@code id="..."} 属性，否则搜引用原文串。 */
    public boolean anchorIsFragment() {
        String fragment = detailFields.get("fragment");
        return fragment != null && !fragment.isBlank();
    }

    /** 原始问题对象，供定位逻辑取 {@code resourceHref}。 */
    public ValidationIssue issue() {
        return issue;
    }

    @Override
    public String toString() {
        return getCode() + " " + getSeverity() + " " + getLocation() + "：" + getMessage();
    }

    private static String stripFragment(String value) {
        int hash = value.indexOf('#');
        return hash < 0 ? value : value.substring(0, hash);
    }

    /** {@code detail} 形如 {@code "context=img/@src, target=miss.png"}，按逗号与首个等号切分。 */
    private static Map<String, String> parseDetail(String detail) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (detail == null || detail.isBlank()) {
            return fields;
        }
        for (String part : detail.split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).trim();
            String value = part.substring(eq + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                fields.put(key, value);
            }
        }
        return fields;
    }
}
