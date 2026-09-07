package org.chobit.epubra.app.controller.workflow;

import org.chobit.epubra.app.controller.workflow.DocumentController;
import org.chobit.epubra.app.support.platform.AsyncTasks;
import org.chobit.epubra.app.support.context.AppEventBus;
import org.chobit.epubra.app.support.document.Autosave;
import org.chobit.epubra.app.support.context.BookContext;
import org.chobit.epubra.app.support.platform.PreferenceNodes;
import org.chobit.epubra.app.support.workspace.WorkspaceStore;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「点击工作空间里的 .draft 卡片 → 直接进入该文档编辑页」的契约测试。
 *
 * <p>覆盖 {@link DocumentController#openDraft(Path)} /
 * {@link DocumentController#openDraftAsync(Path)} 三条约定：
 * <ol>
 *   <li>`.draft` 能被直接读成 Book（内容就是合法 EPUB zip），并落到 ctx</li>
 *   <li>状态栏文案去掉 `.draft` 后缀</li>
 *   <li>打开后记住文档所在目录为「当前工作空间」，下次启动可直达</li>
 * </ol>
 */
class OpenDraftTest {

    private static DocumentController.FileChooserOpener noopDialogs() {
        return new DocumentController.FileChooserOpener() {
            @Override
            public File showOpenDialog() { return null; }
            @Override
            public File showSaveDialog(String initialName) { return null; }
        };
    }

    @TempDir
    Path workspace;

    @BeforeEach
    void setUpStore() {
        PreferenceNodes.useInMemoryForTesting();
        WorkspaceStore.resetForTesting();
    }

    @AfterEach
    void tearDownStore() {
        WorkspaceStore.resetForTesting();
        PreferenceNodes.resetForTesting();
    }

    /** 在工作空间里造一份真实可读的 .draft（内容 = 合法 EPUB zip）。 */
    private Path writeDraft(String name, String title) throws IOException {
        Book book = BookFactory.createEmpty(title);
        Path target = workspace.resolve(name);
        new EpubWriter().write(book, target);
        return target;
    }

    private DocumentController controller(BookContext ctx, AtomicReference<String> status) {
        return new DocumentController(ctx, status::set, () -> true,
                noopDialogs(), AsyncTasks.NOOP_PROGRESS, s -> { });
    }

    @Test
    void openDraftLoadsBookIntoContext() throws IOException {
        Path draft = writeDraft("三体.draft", "三体");
        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();
        AtomicInteger loadEvents = new AtomicInteger();
        ctx.bus().subscribe(AppEventBus.BookLoadedEvent.class,
                e -> loadEvents.incrementAndGet());

        controller(ctx, status).openDraft(draft);

        assertNotNull(ctx.book(), ".draft 应被读成 Book 并载入 ctx");
        assertEquals("三体", ctx.book().metadata().firstTitle());
        assertEquals(draft, ctx.currentFile(), "currentFile 应指向该 .draft，后续 Ctrl+S 写回它");
        assertEquals(1, loadEvents.get(), "必须广播 BookLoadedEvent——宫格据此收起、编辑区呈现");
    }

    @Test
    void statusMessageStripsDraftSuffix() throws IOException {
        Path draft = writeDraft("球状闪电.draft", "球状闪电");
        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();

        controller(ctx, status).openDraft(draft);

        assertEquals("已打开 球状闪电", status.get(),
                "状态栏应去掉 .draft 后缀，不显示扩展名");
    }

    @Test
    void openingDraftRemembersItsWorkspaceForNextLaunch() throws IOException {
        Path draft = writeDraft("朝闻道.draft", "朝闻道");
        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();

        assertTrue(WorkspaceStore.last().isEmpty(), "前置：尚未记录工作空间");

        controller(ctx, status).openDraft(draft);

        assertEquals(workspace, WorkspaceStore.last().orElseThrow(),
                "打开 .draft 后应把文档所在目录记为当前工作空间（下次启动直达）");
    }

    @Test
    void draftDisplayNameStripsSuffixOnly() {
        assertEquals("三体", DocumentController.draftDisplayName(Path.of("D:/ws/三体.draft")));
        // 不带后缀时原样返回——不强制要求调用方先校验
        assertEquals("notes", DocumentController.draftDisplayName(Path.of("notes")));
        assertEquals("", DocumentController.draftDisplayName(null));
    }

    @Test
    void openDraftOnMissingFileThrowsAndLeavesContextUntouched() {
        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();
        Path missing = workspace.resolve("不存在.draft");

        assertThrows(IOException.class,
                () -> controller(ctx, status).openDraft(missing),
                "文档不存在应抛 IOException，让调用方留在宫格而不是进空编辑页");

        assertEquals(null, ctx.book(), "失败时不应把 ctx 弄脏");
        assertEquals(null, ctx.currentFile());
        assertTrue(WorkspaceStore.last().isEmpty(), "失败时不应记录工作空间");
    }

    @Test
    void openDraftResetsDirtyAndHistory() throws IOException {
        Path draft = writeDraft("clean.draft", "干净文档");
        BookContext ctx = new BookContext();
        AtomicReference<String> status = new AtomicReference<>();
        // 先制造"已有未保存修改"的假象
        ctx.setDirty(true);

        controller(ctx, status).openDraft(draft);

        assertEquals(false, ctx.dirty(), "刚打开的文档不应是脏的");
        assertEquals(false, ctx.history().canUndo(), "历史应被重置——旧书的撤销栈不能跨文档沿用");
    }

    @Test
    void draftFileIsReadableAsEpubBecauseItIsOne() throws IOException {
        // 核心前提：.draft 的内容就是合法 EPUB zip，EpubReader 无需任何适配。
        // 这条断言是"点击直达编辑页"能成立的技术基础。
        Path draft = writeDraft("proof.draft", "证明");
        byte[] head = Files.readAllBytes(draft);

        assertEquals('P', head[0] & 0xFF, ".draft 应以 PK zip 魔数开头");
        assertEquals('K', head[1] & 0xFF);
        assertEquals(Autosave.DRAFT_SUFFIX, ".draft");
    }
}
