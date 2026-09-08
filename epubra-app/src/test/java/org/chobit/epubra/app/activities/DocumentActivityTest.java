package org.chobit.epubra.app.activities;

import org.chobit.epubra.app.context.AppEventBus.BookLoadedEvent;
import org.chobit.epubra.app.context.AppEventBus.BookSavedEvent;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.io.EpubWriter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentActivityTest {

    private static DocumentActivity.FileChooserOpener noopDialogs() {
        return new DocumentActivity.FileChooserOpener() {
            @Override
            public File showOpenDialog() {
                return null;
            }

            @Override
            public File showSaveDialog(String initialName) {
                return null;
            }
        };
    }

    @TempDir
    Path workspace;

    @BeforeEach
    void resetStores() {
        WorkspaceStore.resetForTesting();
    }

    @AfterEach
    void cleanStores() {
        WorkspaceStore.resetForTesting();
    }

    @Test
    void newBookResetsContextAndBroadcastsLoadedEvent() {
        BookContext ctx = new BookContext();
        AtomicInteger loadEvents = new AtomicInteger();
        AtomicReference<String> status = new AtomicReference<>();
        ctx.bus().subscribe(BookLoadedEvent.class, e -> loadEvents.incrementAndGet());
        DocumentActivity doc = new DocumentActivity(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        doc.newBook();

        assertNotNull(ctx.book());
        assertNull(ctx.currentFile());
        assertEquals("已新建空白书籍", status.get());
        assertEquals(1, loadEvents.get());
    }

    @Test
    void newDraft_createsDraftFileAndLoadsBook() throws IOException {
        BookContext ctx = new BookContext();
        AtomicInteger loadEvents = new AtomicInteger();
        AtomicReference<String> status = new AtomicReference<>();
        ctx.bus().subscribe(BookLoadedEvent.class, e -> loadEvents.incrementAndGet());
        DocumentActivity doc = new DocumentActivity(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        Path target = doc.newDraft(workspace, "Alpha", "测试标题");

        assertEquals(workspace.resolve("Alpha.draft"), target);
        assertTrue(Files.exists(target));
        assertEquals(target, ctx.currentFile());
        assertEquals("测试标题", ctx.book().metadata().firstTitle());
        assertEquals("已创建图书 Alpha.draft", status.get());
        assertEquals(1, loadEvents.get());
        assertTrue(WorkspaceStore.recentExisting().contains(workspace));
    }

    @Test
    void openFile_importsEpubIntoDraftPath() throws IOException {
        Path source = workspace.resolve("Source.epub");
        Book original = BookFactory.createEmpty("Source");
        new EpubWriter().write(original, source);

        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();
        DocumentActivity doc = new DocumentActivity(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        doc.openFile(source);

        assertEquals(workspace.resolve("Source.draft"), ctx.currentFile());
        assertEquals("已打开 Source", status.get());
        assertTrue(WorkspaceStore.recentExisting().contains(workspace));
    }

    @Test
    void saveToSynchronouslyWritesAndUpdatesContext() throws IOException {
        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();
        AtomicInteger saveEvents = new AtomicInteger();
        ctx.bus().subscribe(BookSavedEvent.class, e -> saveEvents.incrementAndGet());
        DocumentActivity doc = new DocumentActivity(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});
        doc.newBook();
        ctx.setDirty(true);
        Path target = workspace.resolve("saved.draft");

        doc.saveTo(target);

        assertTrue(Files.exists(target));
        assertEquals(target, ctx.currentFile());
        assertEquals(target, ctx.book().source());
        assertFalse(ctx.dirty());
        assertEquals("已保存到 saved.draft", status.get());
        assertEquals(1, saveEvents.get());
    }

    @Test
    void saveToFailureKeepsContextAndReportsError() throws IOException {
        BookContext ctx = new BookContext();
        AtomicReference<String> error = new AtomicReference<>();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, error::set);
        doc.newBook();
        Path original = workspace.resolve("original.draft");
        ctx.setCurrentFile(original);
        ctx.setDirty(true);
        Path invalidTarget = workspace.resolve("cannot-save.draft");
        Files.createDirectory(invalidTarget);

        doc.saveTo(invalidTarget);

        assertTrue(Files.isDirectory(invalidTarget));
        assertEquals(original, ctx.currentFile());
        assertTrue(ctx.dirty());
        assertNotNull(error.get());
        assertTrue(error.get().startsWith("保存失败"));
    }

    @Test
    void onNewRespectsDiscardConfirmation() {
        BookContext ctx = new BookContext();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> false,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        doc.onNew();

        assertNull(ctx.book());
    }
}
