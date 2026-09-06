package org.chobit.epubra.app.support;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.MediaTypes;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.validation.IssueKind;
import org.chobit.epubra.lib.validation.ValidationIssue;
import org.chobit.epubra.lib.validation.ValidationReport;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ValidationOpsTest {

    @Test
    void resolveIssueResourcePrefersDirectHref() {
        Book book = BookFactory.createEmpty("解析");
        Resource chapter = book.spineResources().get(0);
        // 直接用绝对路径找
        assertEquals(chapter, ValidationOps.resolveIssueResource(book, chapter.href()));
        // 相对内容目录形式也找得到
        String relative = book.relativeToContentDirectory(chapter.href());
        assertEquals(chapter, ValidationOps.resolveIssueResource(book, relative));
    }

    @Test
    void resolveIssueResourceReturnsNullForBlankOrUnknown() {
        Book book = BookFactory.createEmpty("空");
        assertNull(ValidationOps.resolveIssueResource(book, null));
        assertNull(ValidationOps.resolveIssueResource(book, ""));
        assertNull(ValidationOps.resolveIssueResource(book, "OEBPS/ghost.xhtml"));
    }

    @Test
    void statusTextReflectsReportCounts() {
        assertEquals("无问题", ValidationOps.statusText(ValidationReport.EMPTY));
        ValidationReport report = new ValidationReport(List.of(
                new ValidationIssue(IssueKind.NAV_PARSE_FAILED, "x", "y"),
                new ValidationIssue(IssueKind.SPINE_ORDER_VS_TOC, "x", "y"),
                new ValidationIssue(IssueKind.SPINE_ORDER_VS_TOC, "x", "y")
        ), true);
        assertEquals("1 个错误 · 2 个警告", ValidationOps.statusText(report));
    }

    @Test
    void scopeSummaryReportsContainerScope() {
        // 空报告：scopeSummary 返回空串
        assertEquals("", ValidationOps.scopeSummary(ValidationReport.EMPTY));
        // 有 issue 的报告：依据 containerChecked 走两条文案
        ValidationReport withIssues = new ValidationReport(List.of(
                new ValidationIssue(IssueKind.NAV_PARSE_FAILED, "x", "y", "detail")
        ), true);
        assertEquals("含容器级规则", ValidationOps.scopeSummary(withIssues));
        ValidationReport memoryOnly = new ValidationReport(List.of(
                new ValidationIssue(IssueKind.NAV_PARSE_FAILED, "x", "y", "detail")
        ), false);
        assertEquals("仅内存校验，保存后可得容器级结果", ValidationOps.scopeSummary(memoryOnly));
    }

    @Test
    void countIssuesAffectingByHref() {
        Resource chapter = new Resource("ch-1", "OEBPS/ch-1.xhtml", MediaTypes.XHTML);
        ValidationReport report = new ValidationReport(List.of(
                new ValidationIssue(IssueKind.NAV_PARSE_FAILED, "msg1", "OEBPS/ch-1.xhtml", "detail"),
                new ValidationIssue(IssueKind.XHTML_NOT_WELL_FORMED, "msg2", "OEBPS/ch-1.xhtml", "detail"),
                new ValidationIssue(IssueKind.MANIFEST_ITEM_EMPTY_DATA, "msg3", "OEBPS/nav.xhtml", "detail")
        ), true);
        assertEquals(2, ValidationOps.countIssuesAffecting(report, "OEBPS/ch-1.xhtml"));
        assertEquals(1, ValidationOps.countIssuesAffecting(report, "OEBPS/nav.xhtml"));
        assertEquals(0, ValidationOps.countIssuesAffecting(report, "OEBPS/ghost.xhtml"));
    }
}
