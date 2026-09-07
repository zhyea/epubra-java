package org.chobit.epubra.app.activities;

import org.chobit.epubra.app.activities.UndoActivity;
import org.chobit.epubra.app.ui.support.context.AppEventBus.BookRestoredEvent;
import org.chobit.epubra.app.ui.support.context.BookContext;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link UndoActivity} 的契约测试。
 *
 * <p>所有实例通过 {@link UndoActivity#forTesting} 创建，避开 PauseTransition 触发的
 * JavaFX Toolkit 初始化。
 *
 * <p>撤销 / 重做后的 UI 重画通过 {@link BookRestoredEvent} 广播，测试里直接订阅事件
 * 计数来替代真实 UI 刷新。
 */
class UndoActivityTest {

    private static UndoActivity newUndo(BookContext ctx,
                                          UndoActivity.StatusSink status,
                                          UndoActivity.ValidationClearer clearer) {
        return UndoActivity.forTesting(ctx, status, clearer);
    }

    @Test
    void beginChangeThenUndoRestoresPriorSnapshot() {
        BookContext ctx = new BookContext();
        Book initial = BookFactory.createEmpty("initial");
        ctx.setBook(initial);

        AtomicInteger restoreEvents = new AtomicInteger();
        AtomicReference<String> lastStatus = new AtomicReference<>();
        AtomicInteger flushChapter = new AtomicInteger();
        AtomicInteger flushMeta = new AtomicInteger();
        ctx.bus().subscribe(BookRestoredEvent.class, e -> restoreEvents.incrementAndGet());
        UndoActivity undo = newUndo(ctx, lastStatus::set, () -> {});
        undo.installFlushCallbacks(flushChapter::incrementAndGet, flushMeta::incrementAndGet);

        // 第一次「变更前」拍快照
        undo.beginChange();

        // 模拟修改书：往 initial 里加一个章节（createEmpty 预置 1 个，加后变 2）
        initial.addChapter("新章节", null);
        assertEquals(2, initial.spine().size());

        // 撤销应回到只有 1 个预置章节
        undo.undo();
        assertEquals(1, ctx.book().spine().size(), "撤销后书应回到原始快照");
        assertEquals("已撤销", lastStatus.get());
        assertTrue(restoreEvents.get() >= 1, "撤销后必须广播 BookRestoredEvent");
    }

    @Test
    void noOpUndoSetsStatus() {
        BookContext ctx = new BookContext();
        ctx.setBook(BookFactory.createEmpty("初始"));
        AtomicReference<String> lastStatus = new AtomicReference<>();
        UndoActivity undo = newUndo(ctx, lastStatus::set, () -> {});
        undo.installFlushCallbacks(() -> {}, () -> {});

        undo.undo();

        assertEquals("没有可撤销的操作", lastStatus.get());
    }

    @Test
    void noOpRedoSetsStatus() {
        BookContext ctx = new BookContext();
        ctx.setBook(BookFactory.createEmpty("初始"));
        AtomicReference<String> lastStatus = new AtomicReference<>();
        UndoActivity undo = newUndo(ctx, lastStatus::set, () -> {});
        undo.installFlushCallbacks(() -> {}, () -> {});

        undo.redo();

        assertEquals("没有可重做的操作", lastStatus.get());
    }

    @Test
    void restoreClearsValidation() {
        BookContext ctx = new BookContext();
        ctx.setBook(BookFactory.createEmpty("初始"));
        AtomicInteger validationCleared = new AtomicInteger();
        UndoActivity undo = newUndo(ctx, s -> {}, validationCleared::incrementAndGet);
        undo.installFlushCallbacks(() -> {}, () -> {});

        undo.beginChange();
        undo.undo();

        assertTrue(validationCleared.get() >= 1, "撤销后必须清掉旧校验结果");
    }

    @Test
    void installFlushCallbacksEnablesNoArgUndoRedo() {
        BookContext ctx = new BookContext();
        ctx.setBook(BookFactory.createEmpty("初始"));
        AtomicInteger flushChapter = new AtomicInteger();
        AtomicInteger flushMeta = new AtomicInteger();
        UndoActivity undo = newUndo(ctx, s -> {}, () -> {});
        undo.installFlushCallbacks(flushChapter::incrementAndGet, flushMeta::incrementAndGet);

        undo.beginChange();
        // beginChange 会 commitPendingEdits → 触发 flush 一次
        assertTrue(flushChapter.get() >= 1);
        assertTrue(flushMeta.get() >= 1);

        undo.undo();
        // 撤销路径上 commitPendingEdits 也应触发 flush
        assertTrue(flushChapter.get() >= 2);
    }
}
