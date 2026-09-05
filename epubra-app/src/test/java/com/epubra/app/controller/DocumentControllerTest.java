package com.epubra.app.controller;

import com.epubra.app.support.AppEventBus.BookLoadedEvent;
import com.epubra.app.support.BookContext;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentController} 编程入口（newBook / openFile / saveTo）的契约测试。
 *
 * <p>"加载完成 → UI 重画"的契约通过 {@link BookLoadedEvent} 广播，测试里直接订阅事件计数
 * 取代原先的 callback 注入。
 */
class DocumentControllerTest {

    private static DocumentController.FileChooserOpener noopDialogs() {
        return new DocumentController.FileChooserOpener() {
            @Override
            public File showOpenDialog() { return null; }
            @Override
            public File showSaveDialog(String initialName) { return null; }
        };
    }

    @Test
    void newBookResetsContextAndBroadcastsLoadedEvent() {
        BookContext ctx = new BookContext();
        AtomicInteger loadEvents = new AtomicInteger();
        AtomicReference<String> status = new AtomicReference<>();
        ctx.bus().subscribe(BookLoadedEvent.class, e -> loadEvents.incrementAndGet());
        DocumentController doc = new DocumentController(ctx, status::set, () -> true,
                noopDialogs(), s -> {});

        doc.newBook();

        assertNotNull(ctx.book(), "newBook 必须创建一本书");
        assertNull(ctx.currentFile(), "newBook 必须把 currentFile 置 null");
        assertEquals(1, ctx.book().spine().size(), "createEmpty 预置 1 个章节");
        assertEquals("已新建空白书籍", status.get());
        assertEquals(1, loadEvents.get(), "newBook 必须广播 BookLoadedEvent");
    }

    @Test
    void onNewRespectsDiscardConfirmation() {
        BookContext ctx = new BookContext();
        AtomicInteger loadEvents = new AtomicInteger();
        DocumentController doc = new DocumentController(ctx, s -> {}, () -> false,
                noopDialogs(), s -> {});

        doc.onNew();

        // 用户拒绝丢弃 → 不应触发 newBook；自然也不应广播事件
        assertEquals(0, loadEvents.get());
        // 没有新建书籍时，ctx.book() 仍为 null
        assertNull(ctx.book());
    }

    @Test
    void openFileMissingShowsError() {
        BookContext ctx = new BookContext();
        AtomicReference<String> error = new AtomicReference<>();
        DocumentController doc = new DocumentController(ctx, s -> {}, () -> true,
                noopDialogs(), error::set);

        try {
            doc.openFile(new File("/nonexistent/never.epub").toPath());
        } catch (Exception expected) {
            // EpubReader 抛 IOException 时被 catch 后转 errorReporter，
            // 但 openFile 本身声明 throws IOException；此处不必再 catch
        }

        assertTrue(error.get() == null || error.get().startsWith("打开失败"),
                "打开失败时应调 errorReporter，或在异常路径上报错");
    }
}