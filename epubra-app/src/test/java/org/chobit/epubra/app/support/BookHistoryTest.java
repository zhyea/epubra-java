package org.chobit.epubra.app.support;

import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.validation.EpubValidator;
import org.chobit.epubra.lib.validation.ValidationReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 快照式撤销 / 重做：章节增删、正文改动与 dirty 标志的还原。
 */
class BookHistoryTest {

    @Test
    @DisplayName("撤销可回退新增的章节")
    void undoRevertsAddedChapter() {
        Book book = BookFactory.createEmpty("测试书籍");
        int original = book.spineResources().size();

        BookHistory history = new BookHistory();
        history.record(book, false);
        book.addChapter("第二章", null);
        assertEquals(original + 1, book.spineResources().size());

        BookHistory.Snapshot snapshot = history.undo(book, true);
        assertNotNull(snapshot);
        assertEquals(original, snapshot.book().spineResources().size());
        // 记录时处于「已保存」状态，撤销后应回到未修改
        assertFalse(snapshot.dirty());
    }

    @Test
    @DisplayName("重做可恢复被撤销的章节")
    void redoRestoresUndoneChange() {
        Book book = BookFactory.createEmpty("测试书籍");
        int original = book.spineResources().size();

        BookHistory history = new BookHistory();
        history.record(book, false);
        book.addChapter("第二章", null);

        BookHistory.Snapshot undone = history.undo(book, true);
        assertEquals(original, undone.book().spineResources().size());

        BookHistory.Snapshot redone = history.redo(undone.book(), undone.dirty());
        assertNotNull(redone);
        assertEquals(original + 1, redone.book().spineResources().size());
        assertTrue(redone.dirty());
    }

    @Test
    @DisplayName("撤销可还原章节正文的改动")
    void undoRevertsChapterText() {
        Book book = BookFactory.createEmpty("测试书籍");
        String originalText = book.spineResources().get(0).asString();

        BookHistory history = new BookHistory();
        history.record(book, false);
        book.spineResources().get(0).setString("<html><body><p>改过的内容</p></body></html>");

        BookHistory.Snapshot snapshot = history.undo(book, true);
        assertNotNull(snapshot);
        assertEquals(originalText, snapshot.book().spineResources().get(0).asString());
    }

    @Test
    @DisplayName("撤销可还原元数据的改动")
    void undoRevertsMetadata() {
        Book book = BookFactory.createEmpty("原名");
        BookHistory history = new BookHistory();
        history.record(book, false);
        book.metadata().setFirstTitle("改名后");

        BookHistory.Snapshot snapshot = history.undo(book, true);
        assertNotNull(snapshot);
        assertEquals("原名", snapshot.book().metadata().firstTitle());
    }

    @Test
    @DisplayName("空历史时撤销与重做返回 null")
    void emptyHistoryReturnsNull() {
        Book book = BookFactory.createEmpty("测试书籍");
        BookHistory history = new BookHistory();

        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
        assertNull(history.undo(book, false));
        assertNull(history.redo(book, false));
    }

    @Test
    @DisplayName("换书后历史被清空")
    void resetClearsHistory() {
        Book book = BookFactory.createEmpty("测试书籍");
        BookHistory history = new BookHistory();
        history.record(book, false);
        assertTrue(history.canUndo());

        history.reset();
        assertFalse(history.canUndo());
        assertFalse(history.canRedo());
    }

    @Test
    @DisplayName("新记录会清空重做栈，避免分支历史错乱")
    void newRecordClearsRedoStack() {
        Book book = BookFactory.createEmpty("测试书籍");
        BookHistory history = new BookHistory();

        history.record(book, false);
        book.addChapter("第二章", null);
        history.undo(book, true);
        assertTrue(history.canRedo());

        history.record(book, false);
        assertFalse(history.canRedo());
    }

    @Test
    @DisplayName("多步可连续撤销到最早的状态")
    void undoMultipleSteps() {
        Book book = BookFactory.createEmpty("测试书籍");
        int original = book.spineResources().size();
        BookHistory history = new BookHistory();

        history.record(book, false);
        book.addChapter("第二章", null);
        history.record(book, true);
        book.addChapter("第三章", null);
        assertEquals(original + 2, book.spineResources().size());

        BookHistory.Snapshot step = history.undo(book, true);
        assertEquals(original + 1, step.book().spineResources().size());
        step = history.undo(step.book(), step.dirty());
        assertEquals(original, step.book().spineResources().size());
        assertFalse(history.canUndo());
    }

    @Test
    @DisplayName("记录快照后校验不应因导航文档为空而误报错误")
    void recordDoesNotPoisonValidation() {
        Book book = BookFactory.createEmpty("验证");
        new BookHistory().record(book, false);

        ValidationReport report = new EpubValidator().validate(book);
        assertEquals(0, report.errorCount(),
                "记录一次快照不应往书里塞空资源，校验结果应为干净：" + report);
    }
}
