package com.epubra.app.support;

import com.epubra.app.controller.ValidationIssueRow;
import com.epubra.epublib.validation.IssueKind;
import com.epubra.epublib.validation.ValidationIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验问题行模型：Bean 属性取值与「detail → 定位锚点」的解析。
 *
 * <p>纯逻辑，不碰 JavaFX 控件，无需 JavaFX 运行时。
 */
class ValidationIssueRowTest {

    private static final String HREF = "OEBPS/chapter-1.xhtml";

    @Test
    @DisplayName("级别、分组、位置直接取中文标签")
    void exposesChineseLabels() {
        ValidationIssueRow row = new ValidationIssueRow(
                new ValidationIssue(IssueKind.XHTML_NOT_WELL_FORMED, "文档不是良构的 XML", HREF));

        assertEquals("错误", row.getSeverity());
        assertEquals("资源引用", row.getGroup());
        assertEquals("文档不是良构的 XML", row.getMessage());
        assertEquals(HREF, row.getLocation());
        assertEquals("E01", row.getCode());
        assertTrue(row.isError());
    }

    @Test
    @DisplayName("警告级别的行 isError 为 false，标签取「警告」")
    void exposesWarningSeverity() {
        ValidationIssueRow row = new ValidationIssueRow(
                new ValidationIssue(IssueKind.RESOURCE_ORPHAN, "资源没有被引用", "OEBPS/orphan.png"));

        assertEquals("警告", row.getSeverity());
        assertFalse(row.isError());
    }

    @Test
    @DisplayName("整书级问题没有 href，位置回退为「整书」")
    void locationFallsBackToWholeBook() {
        ValidationIssueRow row = new ValidationIssueRow(
                new ValidationIssue(IssueKind.METADATA_TITLE_MISSING, "书籍缺少标题"));

        assertEquals("整书", row.getLocation());
        assertEquals("", row.getDetail());
        assertEquals("", row.anchor());
        assertFalse(row.anchorIsFragment());
    }

    @Test
    @DisplayName("detail 为空时锚点为空串，且不被当作锚点 id")
    void emptyDetailYieldsEmptyAnchor() {
        ValidationIssueRow row = new ValidationIssueRow(
                new ValidationIssue(IssueKind.XHTML_NOT_WELL_FORMED, "不是良构 XML", HREF, null));

        assertEquals("", row.anchor());
        assertFalse(row.anchorIsFragment());
    }

    @Test
    @DisplayName("detail 里的 fragment 优先作为锚点，且标记为文档内 id")
    void fragmentIsPreferredAnchor() {
        ValidationIssueRow row = new ValidationIssueRow(
                new ValidationIssue(IssueKind.FRAGMENT_MISSING, "锚点 #sec2 不存在", HREF,
                        "context=a/@href, target=chapter-2.xhtml#sec2, fragment=sec2"));

        assertEquals("sec2", row.anchor());
        assertTrue(row.anchorIsFragment());
    }

    @Test
    @DisplayName("没有 fragment 时退回引用原文串，并去掉 # 及其后的片段")
    void fallsBackToTargetWithoutFragment() {
        ValidationIssueRow row = new ValidationIssueRow(
                new ValidationIssue(IssueKind.REFERENCE_TARGET_MISSING, "引用指向的资源不存在", HREF,
                        "context=img/@src, resolved=OEBPS/missing.png#top"));

        assertEquals("OEBPS/missing.png", row.anchor());
        assertFalse(row.anchorIsFragment());
    }

    @Test
    @DisplayName("detail 的键值对解析忽略缺少等号的片段")
    void ignoresMalformedDetailParts() {
        ValidationIssueRow row = new ValidationIssueRow(
                new ValidationIssue(IssueKind.REFERENCE_TARGET_OUTSIDE, "引用越界", HREF,
                        "context=img/@src, 残缺片段, target=../outside.png"));

        assertEquals("../outside.png", row.anchor());
    }

    @Test
    @DisplayName("悬浮提示包含规则编号、说明与技术细节")
    void tooltipCarriesCodeMessageAndDetail() {
        ValidationIssueRow row = new ValidationIssueRow(
                new ValidationIssue(IssueKind.REFERENCE_TARGET_MISSING, "引用指向的资源不存在", HREF,
                        "context=img/@src, target=miss.png"));

        String tooltip = row.tooltipText();
        assertTrue(tooltip.startsWith("E02"), tooltip);
        assertTrue(tooltip.contains("引用指向的资源不存在"), tooltip);
        assertTrue(tooltip.contains("context=img/@src, target=miss.png"), tooltip);
    }

    @Test
    @DisplayName("保留原始问题对象，供定位逻辑取 resourceHref")
    void keepsOriginalIssue() {
        ValidationIssue issue =
                new ValidationIssue(IssueKind.XHTML_NOT_WELL_FORMED, "不是良构 XML", HREF);
        ValidationIssueRow row = new ValidationIssueRow(issue);

        assertSame(issue, row.issue());
        assertEquals(HREF, row.issue().resourceHref());
    }

    @Test
    @DisplayName("构造时拒绝 null 问题")
    void rejectsNullIssue() {
        assertThrows(NullPointerException.class, () -> new ValidationIssueRow(null));
    }
}
