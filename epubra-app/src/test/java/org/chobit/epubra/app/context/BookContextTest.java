package org.chobit.epubra.app.context;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.validation.ValidationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookContext} 单元测试——这是跨控制器共享状态的唯一持有者，
 * 之前完全没有测试覆盖，换书 / 缓存失效这类状态转换一旦回归会影响所有面板。
 */
class BookContextTest {

    private BookContext ctx;

    @BeforeEach
    void setUp() {
        ctx = new BookContext();
    }

    @Test
    void startsEmpty() {
        assertEquals(null, ctx.book());
        assertEquals(null, ctx.currentFile());
        assertFalse(ctx.dirty(), "新上下文不应是脏的");
        assertFalse(ctx.loading());
        assertFalse(ctx.editCaptured());
        assertSame(ValidationReport.EMPTY, ctx.lastReport(), "未校验前用 EMPTY，避免各处判空");
        assertTrue(ctx.wordCounts().isEmpty());
    }

    @Test
    void bookAndFileAreIndependent() {
        Book book = BookFactory.createEmpty("三体");
        ctx.setBook(book);
        ctx.setCurrentFile(Path.of("D:/ws/三体.draft"));

        assertSame(book, ctx.book());
        assertEquals(Path.of("D:/ws/三体.draft"), ctx.currentFile());
    }

    @Test
    void dirtyFlagRoundTrips() {
        ctx.setDirty(true);
        assertTrue(ctx.dirty());
        ctx.setDirty(false);
        assertFalse(ctx.dirty());
    }

    @Test
    void resetForNewBookClearsDocumentStateButNotWindowState() {
        Book book = BookFactory.createEmpty("球状闪电");
        ctx.setBook(book);
        ctx.setCurrentFile(Path.of("D:/ws/球状闪电.draft"));
        ctx.setDirty(true);
        ctx.setEditCaptured(true);
        ctx.setLastReport(new ValidationReport(java.util.List.of(), true));
        ctx.wordCounts().put(book.spineResources().get(0), 42);

        ctx.resetForNewBook();

        assertFalse(ctx.history().canUndo(), "撤销栈必须清空——旧书的撤销不能跨文档沿用");
        assertFalse(ctx.editCaptured());
        assertFalse(ctx.dirty(), "换书后不应带着脏标记");
        assertSame(ValidationReport.EMPTY, ctx.lastReport(), "旧书的校验结果不能带过来");
        assertTrue(ctx.wordCounts().isEmpty(), "字数缓存按资源身份缓存，换书后必须整体失效");
        // currentFile 由调用方显式清（切工作空间时 MainController 自己置 null），这里不动
        assertNotNull(ctx.currentFile());
    }

    @Test
    void wordCountsUsesIdentityAndCanBeInvalidated() {
        Book book = BookFactory.createEmpty("缓存");
        Resource chapter = book.spineResources().get(0);
        ctx.setBook(book);
        ctx.wordCounts().put(chapter, 100);

        assertEquals(100, ctx.wordCounts().get(chapter));
        ctx.invalidateWordCounts();
        assertTrue(ctx.wordCounts().isEmpty(), "内容被程序化改写后缓存必须失效");
    }

    @Test
    void busIsStableAcrossCalls() {
        assertSame(ctx.bus(), ctx.bus(), "事件总线必须是同一个实例，否则订阅与发布对不上");
    }

    @Test
    void historyIsStableAcrossCalls() {
        assertSame(ctx.history(), ctx.history());
    }

    @Test
    void autosaveDirIsUsableEvenWithoutFile() {
        assertNotNull(ctx.autosaveDir(), "没有当前文件时也要给出自动暂存目录");
    }

    @Test
    void editStepIdleHasSaneDefault() {
        assertEquals(600, ctx.editStepIdle().toMillis(),
                "连续输入 600ms 静默合并是一条约定，改动会影响撤销粒度");
    }
}
