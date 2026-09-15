package org.chobit.epubra.app.activities;

import org.chobit.epubra.app.context.AppEventBus.BookRestoredEvent;
import org.chobit.epubra.app.context.BookContext;
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

    /**
     * 连续几个输入步，每步的快照必须各归各。
     *
     * <p>复现用户报告的两个症状（「撤销可以无限按」「多次撤销后重做的内容缺失」）：
     * 输入只在静默期结束才写回 book，而快照拍的是 book——编辑步结束时若不先落盘，
     * 第二个输入步步首记到的「变更前状态」还是第一步之前的旧值，撤销栈里就堆出
     * 重复的陈旧快照：撤销在旧值之间打转（撤不完），重做又把旧值逐个弹回（内容缺失）。
     *
     * <p>模拟口径：{@code pendingText} ＝ 界面上尚未写回的文本；flush 回调 ＝
     * 真实实现里 flushCurrentChapter 的「把界面文本写进当前章节」；restore 之后界面
     * 跟着快照走，所以每次 undo / redo 后手动把 pendingText 对齐到恢复出的内容。
     */
    @Test
    void consecutiveTypingBurstsStaySeparateUndoSteps() {
        BookContext ctx = new BookContext();
        Book book = BookFactory.createEmpty("测试");
        book.spineResources().get(0).setString("<p>第一段</p>");
        ctx.setBook(book);

        AtomicReference<String> pendingText = new AtomicReference<>("<p>第一段</p>");
        UndoActivity undo = newUndo(ctx, s -> {}, () -> {});
        undo.installFlushCallbacks(
                () -> ctx.book().spineResources().get(0).setString(pendingText.get()),
                () -> {});

        // 编辑步 1：步首记快照（此刻 book 仍是旧值——与真实输入路径一致），步末落盘
        pendingText.set("<p>第一段A</p>");
        undo.onTextInput();
        undo.finishEditStep();

        // 编辑步 2：步首快照必须拿到**上一步的结果**，而不是一直滞留的旧值
        pendingText.set("<p>第一段AB</p>");
        undo.onTextInput();
        undo.finishEditStep();

        // 撤销第一步 → 回到步 1 的结果；撤销第二步 → 回到最初。一步一变，不能打转
        undo.undo();
        assertEquals("<p>第一段A</p>", ctx.book().spineResources().get(0).asString(), "撤销应回到步 1 之后的内容");
        pendingText.set("<p>第一段A</p>");
        undo.undo();
        assertEquals("<p>第一段</p>", ctx.book().spineResources().get(0).asString(), "撤销应回到最初的内容");

        // 重做按原路逐级恢复——中间状态缺失就是用户报告的「重做内容缺失」
        pendingText.set("<p>第一段</p>");
        undo.redo();
        assertEquals("<p>第一段A</p>", ctx.book().spineResources().get(0).asString(), "重做应先回到步 1");
        pendingText.set("<p>第一段A</p>");
        undo.redo();
        assertEquals("<p>第一段AB</p>", ctx.book().spineResources().get(0).asString(), "重做应回到步 2 的完整内容");
    }
}
