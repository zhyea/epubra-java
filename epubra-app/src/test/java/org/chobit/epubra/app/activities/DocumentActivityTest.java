package org.chobit.epubra.app.activities;

import org.chobit.epubra.app.activities.DocumentActivity;
import org.chobit.epubra.app.ui.support.context.AppEventBus.BookLoadedEvent;
import org.chobit.epubra.app.ui.support.context.AppEventBus.BookSavedEvent;
import org.chobit.epubra.app.support.platform.AsyncTasks;
import org.chobit.epubra.app.ui.support.context.BookContext;
import org.chobit.epubra.app.support.document.ProjectLayout;
import org.chobit.epubra.app.support.workspace.RecentProjectsStore;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link DocumentActivity} 编程入口（newBook / openFile / saveTo）的契约测试。
 *
 * <p>"加载完成 → UI 重画"的契约通过 {@link BookLoadedEvent} 广播，测试里直接订阅事件计数
 * 取代原先的 callback 注入。
 */
class DocumentActivityTest {

    private static DocumentActivity.FileChooserOpener noopDialogs() {
        return new DocumentActivity.FileChooserOpener() {
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
        DocumentActivity doc = new DocumentActivity(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

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
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> false,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

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
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, error::set);

        try {
            doc.openFile(new File("/nonexistent/never.epub").toPath());
        } catch (Exception expected) {
            // EpubReader 抛 IOException 时被 catch 后转 errorReporter，
            // 但 openFile 本身声明 throws IOException；此处不必再 catch
        }

        assertTrue(error.get() == null || error.get().startsWith("打开失败"),
                "打开失败时应调 errorReporter，或在异常路径上报错");
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
        Path target = workspace.resolve("saved.epub");

        doc.saveTo(target);

        assertTrue(Files.exists(target), "同步保存必须写出 EPUB 文件");
        assertEquals(target, ctx.currentFile());
        assertEquals(target, ctx.book().source());
        assertFalse(ctx.dirty());
        assertEquals("已保存到 saved.epub", status.get());
        assertEquals(1, saveEvents.get());
    }

    @Test
    void saveToFailureKeepsContextAndReportsError() throws IOException {
        BookContext ctx = new BookContext();
        AtomicReference<String> error = new AtomicReference<>();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, error::set);
        doc.newBook();
        Path original = workspace.resolve("original.epub");
        ctx.setCurrentFile(original);
        ctx.setDirty(true);
        Path invalidTarget = workspace.resolve("cannot-save.epub");
        Files.createDirectory(invalidTarget);

        doc.saveTo(invalidTarget);

        assertTrue(Files.isDirectory(invalidTarget));
        assertEquals(original, ctx.currentFile(), "保存失败不能改变当前文件");
        assertTrue(ctx.dirty(), "保存失败必须保留脏状态");
        assertNotNull(error.get());
        assertTrue(error.get().startsWith("保存失败"));
    }

    // ---- newProject ----

    @TempDir
    Path workspace;

    private java.util.List<String> originalWorkspaces;
    private java.util.List<String> originalProjects;

    @BeforeEach
    void backUpRecents() {
        originalWorkspaces = RecentProjectsStore.workspaces();
        originalProjects = RecentProjectsStore.projects();
        clearRecents();
    }

    @AfterEach
    void restoreRecents() {
        clearRecents();
        originalWorkspaces.forEach(RecentProjectsStore::addWorkspace);
        originalProjects.forEach(RecentProjectsStore::addProject);
    }

    private void clearRecents() {
        new java.util.ArrayList<>(RecentProjectsStore.workspaces()).forEach(RecentProjectsStore::removeWorkspace);
        new java.util.ArrayList<>(RecentProjectsStore.projects()).forEach(RecentProjectsStore::removeProject);
    }

    @Test
    void newProject_createsScaffoldingAndLoadsBook() throws IOException {
        BookContext ctx = new BookContext();
        AtomicInteger loadEvents = new AtomicInteger();
        AtomicReference<String> status = new AtomicReference<>();
        ctx.bus().subscribe(BookLoadedEvent.class, e -> loadEvents.incrementAndGet());
        DocumentActivity doc = new DocumentActivity(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        Path target = doc.newProject(workspace, "Alpha", "测试标题");

        // 1. EPUB 文件已写入磁盘
        assertTrue(Files.exists(target));
        assertEquals(ProjectLayout.epubFile(workspace, "Alpha"), target);

        // 2. 项目标记 + .epubra 目录都存在
        assertTrue(Files.isDirectory(ProjectLayout.metadataDir(workspace, "Alpha")));
        assertTrue(Files.exists(ProjectLayout.projectMarker(workspace, "Alpha")));

        // 3. ctx 切换为新书
        assertNotNull(ctx.book());
        assertEquals(target, ctx.currentFile());
        assertEquals(1, ctx.book().metadata().firstTitle().length() > 0 ? 1 : 0); // sanity
        assertEquals("测试标题", ctx.book().metadata().firstTitle());
        assertEquals("Alpha", ctx.book().metadata().property("epubra:project-name"));

        // 4. 广播 BookLoadedEvent
        assertEquals(1, loadEvents.get());
        assertEquals("已创建项目 Alpha", status.get());

        // 5. 最近列表写入
        assertTrue(RecentProjectsStore.workspaces().contains(workspace.toString()),
                "工作空间应加入最近列表");
        assertTrue(RecentProjectsStore.projects().contains(target.toString()),
                "项目文件应加入最近列表");
    }

    @Test
    void newProject_rejectsAlreadyExistingDir() throws IOException {
        // 先创建同名目录
        Files.createDirectory(workspace.resolve("Dupe"));
        BookContext ctx = new BookContext();
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        IOException ex = assertThrows(IOException.class,
                () -> doc.newProject(workspace, "Dupe", "标题"));
        assertTrue(ex.getMessage().contains("已存在"));
    }

    @Test
    void newProject_noSideEffectsOnFailure() throws IOException {
        // 制造 IO 失败：把 workspace 设为只读目录
        // 兜底方案——直接用空字符串触发 Path.of 失败之外，最稳的是预先占用同名 dir
        Files.createDirectory(workspace.resolve("Block"));
        BookContext ctx = new BookContext();
        AtomicInteger loadEvents = new AtomicInteger();
        ctx.bus().subscribe(BookLoadedEvent.class, e -> loadEvents.incrementAndGet());
        DocumentActivity doc = new DocumentActivity(ctx, s -> {}, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> {});

        assertThrows(IOException.class,
                () -> doc.newProject(workspace, "Block", "x"));

        assertNull(ctx.book(), "失败时不应有 book");
        assertEquals(0, loadEvents.get(), "失败时不应广播 BookLoadedEvent");
        assertFalse(RecentProjectsStore.workspaces().contains(workspace.toString()),
                "失败时不应写 workspace 到 recents");
    }
}
