package org.chobit.epubra.app.activities;

import org.chobit.epubra.app.context.AppEventBus.BookLoadedEvent;
import org.chobit.epubra.app.context.AppEventBus.BookSavedEvent;
import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.app.document.Autosave;
import org.chobit.epubra.app.platform.AsyncTasks;
import org.chobit.epubra.app.ui.model.NewDraftResult;
import org.chobit.epubra.app.workspace.WorkspaceStore;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.io.EpubReader;
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
        assertTrue(Files.exists(workspace.resolve("Source.draft")));
        assertEquals("Source", new EpubReader().read(workspace.resolve("Source.draft"))
                .metadata().firstTitle());
    }

    @Test
    void importEpubWritesAnEditableDraft() throws IOException {
        Path source = workspace.resolve("原书.epub");
        new EpubWriter().write(BookFactory.createEmpty("原书标题"), source);

        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();
        DocumentActivity doc = new DocumentActivity(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        Path draft = doc.importDraft(workspace, "导入副本", "导入副本标题",
                NewDraftResult.Mode.EPUB, source);

        assertEquals(workspace.resolve("导入副本.draft"), draft);
        assertTrue(Files.exists(draft));
        assertEquals(draft, ctx.currentFile());
        assertEquals("原书标题", ctx.book().metadata().firstTitle(),
                "导入 EPUB 时保留源书标题，避免文件名覆盖元数据");
        assertEquals("原书标题", new EpubReader().read(draft).metadata().firstTitle());
    }

    @Test
    void importTxtCreatesReadableDraftWithTextChapter() throws IOException {
        Path source = workspace.resolve("notes.txt");
        Files.writeString(source, "第一行\n第二行", java.nio.charset.StandardCharsets.UTF_8);

        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();
        DocumentActivity doc = new DocumentActivity(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        Path draft = doc.importDraft(workspace, "笔记", "TXT 笔记",
                NewDraftResult.Mode.TXT, source);
        Book loaded = new EpubReader().read(draft);

        assertEquals(workspace.resolve("笔记.draft"), draft);
        assertEquals("TXT 笔记", loaded.metadata().firstTitle());
        assertEquals(1, loaded.spineResources().size());
        assertTrue(loaded.spineResources().get(0).asString().contains("第一行"));
        assertTrue(loaded.spineResources().get(0).asString().contains("第二行"));
    }

    @Test
    void importTxtReadsCommonWindowsChineseEncoding() throws IOException {
        Path source = workspace.resolve("notes-gbk.txt");
        Files.write(source, "中文内容\n第二行".getBytes(java.nio.charset.Charset.forName("GB18030")));

        BookContext ctx = new BookContext();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        Path draft = doc.importDraft(workspace, "GBK 笔记", "GBK 笔记",
                NewDraftResult.Mode.TXT, source);
        Book loaded = new EpubReader().read(draft);

        assertTrue(loaded.spineResources().get(0).asString().contains("中文内容"));
        assertTrue(loaded.spineResources().get(0).asString().contains("第二行"));
    }

    @Test
    void openTxtAlsoCreatesDraftWithImportedText() throws IOException {
        Path source = workspace.resolve("直接打开.txt");
        Files.write(source, "通过打开入口导入".getBytes(java.nio.charset.Charset.forName("GB18030")));

        BookContext ctx = new BookContext();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        doc.openFile(source);

        assertEquals(workspace.resolve("直接打开.draft"), ctx.currentFile());
        assertTrue(ctx.book().spineResources().get(0).asString().contains("通过打开入口导入"));
        assertTrue(Files.exists(workspace.resolve("直接打开.draft")));
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

    /**
     * B2 守卫：自动暂存（{@link Autosave#flushNow}）会给 Book 打上
     * {@code dcterms:status=draft} + {@code epubra:autosaved-at}，另存为正式 {@code .epub}
     * 时必须擦掉——否则用户把这个文件当正式版发布，包内却留着「草稿」标记。
     */
    @Test
    void saveToFormalEpubStripsDraftMarkers() throws IOException {
        BookContext ctx = new BookContext();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});
        doc.newBook();
        Autosave.markDraft(ctx.book());
        assertTrue(Autosave.isMarkedDraft(ctx.book()), "前置条件：草稿标记应当已打上");

        Path epub = workspace.resolve("正式版.epub");
        doc.saveTo(epub);

        assertFalse(Autosave.isMarkedDraft(ctx.book()), "另存为正式 EPUB 后不该残留草稿标记");
        // 不能只看内存对象——落盘的字节里也必须没有，读回来验一遍
        Book reread = new EpubReader().read(epub);
        assertFalse(Autosave.isMarkedDraft(reread),
                "写出的正式包内不得含 dcterms:status=draft");
    }

    /** 与上一条对称：目标是 {@code .draft} 时标记必须保留，它本身就是草稿。 */
    @Test
    void saveToDraftKeepsDraftMarkers() throws IOException {
        BookContext ctx = new BookContext();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});
        doc.newBook();
        Autosave.markDraft(ctx.book());

        Path draft = workspace.resolve("续写.draft");
        doc.saveTo(draft);

        assertTrue(Autosave.isMarkedDraft(ctx.book()),
                "保存到 .draft 时草稿标记必须保留");
    }

    /**
     * B2 反面守卫：正式另存为失败时，预先擦掉的草稿标记必须回填——
     * 一次失败的另存为不该让「草稿状态」凭空消失。
     */
    @Test
    void saveToFormalEpubFailureRestoresDraftMarkers() throws IOException {
        BookContext ctx = new BookContext();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});
        doc.newBook();
        Autosave.markDraft(ctx.book());

        Path unwritable = workspace.resolve("正式版目录.epub");
        Files.createDirectory(unwritable);

        doc.saveTo(unwritable);

        assertTrue(Files.isDirectory(unwritable));
        assertTrue(Autosave.isMarkedDraft(ctx.book()),
                "另存为失败后草稿标记必须回填");
        assertNotNull(ctx.book().metadata().property(Autosave.AUTOSAVED_AT_PROPERTY),
                "时间戳属性也要一起回填，否则恢复提示会显示不出暂存时间");
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
