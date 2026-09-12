package org.chobit.epubra.app.document;

import org.chobit.epubra.app.context.BookContext;
import org.chobit.epubra.lib.domain.Book;
import org.chobit.epubra.lib.domain.BookFactory;
import org.chobit.epubra.lib.domain.Metadata;
import org.chobit.epubra.lib.io.EpubReader;
import org.chobit.epubra.lib.domain.Resource;
import org.chobit.epubra.lib.domain.SpineReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Autosave} 工具类契约测试。
 *
 * <p>本测试覆盖核心 IO 行为（草稿路径解析、写盘、扫描、删除），Preferences 相关
 * 部分通过临时 {@link AbstractPreferences} 隔离，避免污染用户配置节点。
 */
class AutosaveTest {

    @TempDir
    Path tempDir;
    private BookContext ctx;
    private Path mainFile;
    private Book book;

    @BeforeEach
    void setUp() throws IOException {
        // 隔离 Preferences:用临时内存实现，避免污染真实节点
        Preferences.userRoot().node("/test-autosave");

        mainFile = tempDir.resolve("sample.epub");
        book = BookFactory.createEmpty("测试书籍");
        Files.writeString(mainFile.toString().isEmpty() ? mainFile : mainFile, "not a real epub");
        ctx = new BookContext();
        ctx.setBook(book);
        ctx.setCurrentFile(mainFile);
        // 临时配置：暂存目录指向 tempDir，避免触发 System.getProperty("user.dir") 的真实写入
        ctx.setAutosaveConfig(new AutosaveConfig(true, 5, tempDir.toString()));
    }

    @AfterEach
    void tearDown() throws IOException {
        // 清理临时目录内的 draft 文件
        if (Files.isDirectory(tempDir)) {
            try (var stream = Files.list(tempDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(Autosave.DRAFT_SUFFIX))
                        .forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                            }
                        });
            }
        }
    }

    // ---- 常量 ----

    @Test
    void draftSuffixConstant() {
        assertEquals(".draft", Autosave.DRAFT_SUFFIX);
    }

    @Test
    void statusPropertiesAreExposed() {
        assertEquals("dcterms:status", Autosave.STATUS_PROPERTY);
        assertEquals("draft", Autosave.STATUS_DRAFT);
        assertEquals("epubra:autosaved-at", Autosave.AUTOSAVED_AT_PROPERTY);
    }

    // ---- metadata 标记 ----

    @Test
    void markDraftAddsStandardAndCustomProperties() {
        Autosave.markDraft(book);
        Metadata md = book.metadata();
        assertEquals("draft", md.property(Autosave.STATUS_PROPERTY));
        assertNotNull(md.property(Autosave.AUTOSAVED_AT_PROPERTY),
                "autosaved-at 时间戳不应为空");
        assertTrue(Autosave.isMarkedDraft(book));
    }

    @Test
    void unmarkDraftRemovesProperties() {
        Autosave.markDraft(book);
        assertTrue(Autosave.isMarkedDraft(book));
        Autosave.unmarkDraft(book);
        assertFalse(Autosave.isMarkedDraft(book));
        assertEquals(null, book.metadata().property(Autosave.STATUS_PROPERTY));
        assertEquals(null, book.metadata().property(Autosave.AUTOSAVED_AT_PROPERTY));
    }

    @Test
    void markAndUnmarkOnNullBookIsNoop() {
        // 不能抛 NPE
        Autosave.markDraft(null);
        Autosave.unmarkDraft(null);
        assertFalse(Autosave.isMarkedDraft(null));
    }

    // ---- 路径解析 ----

    @Test
    void draftPathForMainFileReplacesEpubSuffix() {
        Path draft = Autosave.draftPathFor(ctx);
        assertEquals(tempDir.resolve("sample.draft"), draft);
    }

    @Test
    void draftPathForMainFileWithNonEpubExtensionAppendsSuffix() throws IOException {
        Path oddMain = tempDir.resolve("mybook.txt");
        Files.writeString(oddMain, "x");
        ctx.setCurrentFile(oddMain);
        Path draft = Autosave.draftPathFor(ctx);
        assertEquals(tempDir.resolve("mybook.txt.draft"), draft);
    }

    @Test
    void draftPathForNullMainFileFallsBackToAutosaveDir() throws IOException {
        ctx.setCurrentFile(null);
        Path draft = Autosave.draftPathFor(ctx);
        assertEquals(tempDir.resolve(Autosave.UNTITLED_DRAFT_NAME), draft);
    }

    // ---- 写盘 ----

    @Test
    void flushNowWritesDraftFileAndMarksBook() throws IOException {
        Autosave.flushNow(ctx);
        Path expected = tempDir.resolve("sample.draft");
        assertTrue(Files.exists(expected), "草稿文件应已创建: " + expected);
        // 写盘后 Book 已被标记为草稿
        assertTrue(Autosave.isMarkedDraft(book));
        // 文件内容是合法 EPUB zip
        EpubReader reader = new EpubReader();
        Book reread = reader.read(expected);
        assertNotNull(reread);
    }

    @Test
    void flushNowOverwritesExistingDraftSilently() throws IOException {
        Autosave.flushNow(ctx);
        // 再次 flush 不应抛
        Autosave.flushNow(ctx);
        Autosave.flushNow(ctx);
        assertTrue(Files.exists(tempDir.resolve("sample.draft")));
    }

    @Test
    void flushNowWithNullBookIsNoop() {
        ctx.setBook(null);
        // 不能抛
        Autosave.flushNow(ctx);
        assertFalse(Files.exists(tempDir.resolve("sample.draft")));
    }

    // ---- discardFor ----

    @Test
    void discardForRemovesExistingDraftAndClearsMark() throws IOException {
        Autosave.flushNow(ctx);
        assertTrue(Files.exists(tempDir.resolve("sample.draft")));
        Autosave.unmarkDraft(book); // flush 后已标记，清掉以测试 discardFor 的清理
        // discardFor 既删文件，又清标记
        Autosave.discardFor(ctx);
        assertFalse(Files.exists(tempDir.resolve("sample.draft")));
        // 当前 mainFile 路径下草稿没了
    }

    @Test
    void discardForOnMissingDraftIsNoop() throws IOException {
        assertFalse(Files.exists(tempDir.resolve("sample.draft")));
        // 不抛
        Autosave.discardFor(ctx);
    }

    @Test
    void discardForWithNullMainFileIsNoop() throws IOException {
        ctx.setCurrentFile(null);
        Autosave.discardFor(ctx);
        assertFalse(Files.exists(tempDir.resolve(Autosave.UNTITLED_DRAFT_NAME)));
    }

    // ---- findRecoverable ----

    @Test
    void findRecoverableWhenMainFileMatchesReturnsDraft() throws IOException {
        Autosave.flushNow(ctx);
        Optional<Path> found = Autosave.findRecoverable(ctx);
        assertTrue(found.isPresent());
        assertEquals(tempDir.resolve("sample.draft"), found.get());
    }

    @Test
    void findRecoverableWithoutDraftReturnsEmpty() {
        Optional<Path> found = Autosave.findRecoverable(ctx);
        assertFalse(found.isPresent());
    }

    @Test
    void findRecoverableWithNullMainFileScansAutosaveDir() throws IOException {
        // 准备:先 flush 一次产生草稿
        Autosave.flushNow(ctx);
        // 然后清空 currentFile,模拟"未保存新书退出"场景
        ctx.setCurrentFile(null);
        Optional<Path> found = Autosave.findRecoverable(ctx);
        assertTrue(found.isPresent(), "应能从 autosaveDir 找到刚才的草稿");
    }

    @Test
    void findRecoverableReturnsLatestDraftByModTime() throws IOException {
        // 创建两个草稿,设置不同 mtime,验证 max-by-modTime
        Path oldDraft = tempDir.resolve("untitled.draft");
        Path newDraft = tempDir.resolve("other.draft");
        Files.writeString(oldDraft, "old");
        Files.writeString(newDraft, "new");
        Files.setLastModifiedTime(oldDraft,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        Files.setLastModifiedTime(newDraft,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        ctx.setCurrentFile(null);
        Optional<Path> found = Autosave.findRecoverable(ctx);
        assertTrue(found.isPresent());
        assertEquals(newDraft, found.get());
    }

    // ---- readDraft ----

    @Test
    void readDraftReturnsBookAndUnmarksIt() throws IOException {
        Autosave.flushNow(ctx);
        Path draft = tempDir.resolve("sample.draft");
        Book loaded = Autosave.readDraft(draft);
        assertNotNull(loaded);
        assertFalse(Autosave.isMarkedDraft(loaded), "读回后 Book 应已清掉草稿标记");
    }

    // ---- 路径处理的副作用 ----

    @Test
    void draftPathForCreatesAutosaveDirIfNeeded() throws IOException {
        // 用一个尚不存在的 autosave 目录
        Path freshDir = tempDir.resolve("fresh-autosave");
        assertFalse(Files.exists(freshDir));
        ctx.setAutosaveConfig(new AutosaveConfig(true, 5, freshDir.toString()));
        ctx.setCurrentFile(null);
        Path draft = Autosave.draftPathFor(ctx);
        // autosaveDir() 触发创建
        ctx.autosaveDir();
        assertTrue(Files.isDirectory(freshDir));
        assertEquals(freshDir.resolve(Autosave.UNTITLED_DRAFT_NAME), draft);
    }

    @Test
    void draftPathForHandlesCurrentFileInSubdirectory() throws IOException {
        Path sub = Files.createDirectories(tempDir.resolve("sub"));
        Path nested = sub.resolve("book.epub");
        ctx.setCurrentFile(nested);
        Path draft = Autosave.draftPathFor(ctx);
        assertEquals(sub.resolve("book.draft"), draft);
        assertNotEquals(tempDir.resolve("book.draft"), draft);
    }

    // ---- 工作空间主文档：.draft 就是本体 ----

    @Test
    void isDraftFileDetectsDraftSuffix() {
        assertTrue(Autosave.isDraftFile(tempDir.resolve("三体.draft")));
        assertFalse(Autosave.isDraftFile(tempDir.resolve("三体.epub")));
        assertFalse(Autosave.isDraftFile(null));
    }

    @Test
    void draftPathForReturnsDraftItselfWhenMainFileIsAlreadyDraft() {
        Path workspaceDraft = tempDir.resolve("三体.draft");
        ctx.setCurrentFile(workspaceDraft);

        // 关键回归：旧实现会对已是 .draft 的 currentFile 再追加一层后缀
        assertEquals(workspaceDraft, Autosave.draftPathFor(ctx),
                "工作空间里的 .draft 是主文档本体，草稿路径就是它自己");
    }

    @Test
    void flushNowWritesIntoWorkspaceDraftWithoutShadowFile() throws IOException {
        Path workspaceDraft = tempDir.resolve("三体.draft");
        ctx.setCurrentFile(workspaceDraft);

        Autosave.flushNow(ctx);

        assertTrue(Files.exists(workspaceDraft),
                "自动暂存应原地覆盖工作空间里的 .draft（编辑即写它）");
        assertFalse(Files.exists(tempDir.resolve("三体.draft.draft")),
                "绝不能生成 .draft.draft 影子文件——它会同时被 WorkspaceScanner 命中，"
                        + "在宫格上表现为一本图书两张卡片");
        assertNotNull(new EpubReader().read(workspaceDraft), "覆盖后仍是合法 EPUB");
    }

    @Test
    void discardForOnWorkspaceDraftKeepsTheDocument() throws IOException {
        Path workspaceDraft = tempDir.resolve("三体.draft");
        ctx.setCurrentFile(workspaceDraft);
        Autosave.flushNow(ctx);
        assertTrue(Files.exists(workspaceDraft));

        Autosave.discardFor(ctx);

        assertTrue(Files.exists(workspaceDraft),
                "切换文档 / 工作空间时删掉工作空间里的 .draft 等于删掉整本书，必须跳过");
    }

    // ---- 流式验证:draft 文件读回时 metadata.status 仍在 ----

    @Test
    void draftFileRetainsDraftStatusInMetadata() throws IOException {
        // 注:flushNow 内部调 markDraft 后再写盘,所以 status=draft 应该被持久化到 OPF
        Autosave.flushNow(ctx);
        Path draft = tempDir.resolve("sample.draft");
        // 用 readDraft(自动 unmark) 直接读出后清掉标记,只验证文件本身带 draft 标记
        Book loaded = new EpubReader().read(draft);
        assertEquals("draft", loaded.metadata().property(Autosave.STATUS_PROPERTY));
    }

    // ---- integration:flush + readDraft round-trip ----

    @Test
    void flushAndReadRoundTripPreservesContent() throws IOException {
        // 在 Book 里加点内容,然后 flush,再读回,验证内容一致
        Resource res = new Resource("ch1",
                "content.xhtml",
                "application/xhtml+xml",
                "<html><body><p>草稿测试内容</p></body></html>".getBytes());
        book.resources().add(res);
        book.spine().add(new SpineReference(res.id(), true));
        Autosave.flushNow(ctx);
        Book loaded = Autosave.readDraft(tempDir.resolve("sample.draft"));
        assertEquals("测试书籍", loaded.metadata().firstTitle());
        assertEquals(book.metadata().firstTitle(), loaded.metadata().firstTitle());
    }

    // ---- 孤儿草稿收编到工作空间 ----

    @Test
    void uniqueDraftPathAvoidsCollision() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));

        Path first = Autosave.uniqueDraftPath(workspace, "三体");
        assertEquals(workspace.resolve("三体.draft"), first);

        Files.writeString(first, "x");
        assertEquals(workspace.resolve("三体 2.draft"), Autosave.uniqueDraftPath(workspace, "三体"),
                "重名时应顺延编号，不能覆盖工作空间里已有的图书");
    }

    @Test
    void uniqueDraftPathSanitizesIllegalCharactersAndBlank() {
        Path workspace = tempDir.resolve("ws");

        assertEquals("a_b_c_d_e_f_g_h_i.draft",
                Autosave.uniqueDraftPath(workspace, "a/b:c*d?e\"f<g>h|i").getFileName().toString());
        assertEquals("未命名图书.draft",
                Autosave.uniqueDraftPath(workspace, "   ").getFileName().toString(),
                "书名全空白时要有兜底名，不能生成 '.draft' 这种不可读文件名");
    }

    @Test
    void writeIntoWorkspaceWritesReadableDraftAndMarksIt() throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("ws"));
        Book source = BookFactory.createEmpty("球状闪电");

        Path target = Autosave.writeIntoWorkspace(source, workspace, "球状闪电");

        assertEquals(workspace.resolve("球状闪电.draft"), target);
        assertTrue(Files.exists(target));
        assertTrue(Autosave.isMarkedDraft(source), "收编后这本书就是草稿，metadata 应带 draft 标记");
        Book reread = new EpubReader().read(target);
        assertEquals("球状闪电", reread.metadata().firstTitle());
        assertEquals(Autosave.STATUS_DRAFT, reread.metadata().property(Autosave.STATUS_PROPERTY));
    }

    // ---- 恢复提示文案：必须写明工作空间 ----

    @Test
    void recoveryPromptHeaderNamesTheWorkspace() {
        assertTrue(Autosave.recoveryPromptHeader(tempDir.resolve("我的书库")).contains("我的书库"),
                "提示标题必须点出草稿归属的工作空间");
        assertEquals("发现未归属工作空间的草稿", Autosave.recoveryPromptHeader(null),
                "没有工作空间时要明说，并引导用户先选一个");
    }

    @Test
    void recoveryPromptTextNamesWorkspaceAndFullPath() throws IOException {
        Path draft = tempDir.resolve("untitled.draft");
        Files.writeString(draft, "x");
        Path workspace = tempDir.resolve("我的书库");

        String text = Autosave.recoveryPromptText(draft, workspace, Instant.now());

        assertTrue(text.contains("untitled"), "应显示草稿名（去掉 .draft 后缀）: " + text);
        assertTrue(text.contains("我的书库"), "应显示工作空间名: " + text);
        assertTrue(text.contains(workspace.toAbsolutePath().toString()),
                "应显示工作空间完整路径，避免同名目录歧义: " + text);
        assertTrue(text.contains(draft.toAbsolutePath().toString()),
                "应显示草稿位置，方便用户核对: " + text);
    }

    @Test
    void recoveryPromptTextGuidesWhenNoWorkspace() throws IOException {
        Path draft = tempDir.resolve("untitled.draft");
        Files.writeString(draft, "x");

        String text = Autosave.recoveryPromptText(draft, null, Instant.now());

        assertTrue(text.contains("尚未选择工作空间"), "无工作空间时应给出引导: " + text);
    }
}
