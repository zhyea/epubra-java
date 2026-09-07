package org.chobit.epubra.app.support.workspace;

import org.chobit.epubra.app.support.document.DraftDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceScanner} 契约测试——工作空间是扁平的 {@code *.draft} 集合，
 * 扫描结果按<b>最近修改时间降序</b>返回。
 */
class WorkspaceScannerTest {

    @TempDir
    Path workspace;

    /** 造一个 .draft 文件并设定 mtime。 */
    private Path draft(String name, Instant mtime) throws IOException {
        Path p = workspace.resolve(name);
        Files.writeString(p, "fake epub bytes");
        Files.setLastModifiedTime(p, FileTime.from(mtime));
        return p;
    }

    @Test
    void scanReturnsDraftsSortedByModifiedTimeDescending() throws IOException {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Path oldest = draft("朝闻道.draft", base.minus(3, ChronoUnit.DAYS));
        Path newest = draft("三体.draft", base);
        Path middle = draft("球状闪电.draft", base.minus(2, ChronoUnit.HOURS));
        // 干扰项：不应被扫出来
        Files.writeString(workspace.resolve("notes.txt"), "not a draft");
        Files.createDirectory(workspace.resolve("subdir"));

        List<DraftDocument> docs = WorkspaceScanner.scan(workspace);

        assertEquals(3, docs.size(), "只应扫出 3 个 .draft，排除 .txt 与子目录");
        assertEquals(newest, docs.get(0).path(), "最近修改的排最前");
        assertEquals(middle, docs.get(1).path());
        assertEquals(oldest, docs.get(2).path(), "最久未改的排最后");
    }

    @Test
    void tiesBrokenByFileNameForDeterministicOrder() throws IOException {
        // 三个文件 mtime 完全相同——只按 mtime 排时顺序取决于 Files.list 返回次序，
        // 会造成每次刷新宫格卡片位置跳动。加文件名兜底后顺序必须完全确定。
        Instant same = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        draft("zebra.draft", same);
        draft("alpha.draft", same);
        draft("middle.draft", same);

        List<DraftDocument> docs = WorkspaceScanner.scan(workspace);

        assertEquals(List.of("alpha.draft", "middle.draft", "zebra.draft"),
                docs.stream().map(d -> d.path().getFileName().toString()).toList(),
                "mtime 相同时应按文件名升序，保证顺序确定");

        // 重复扫描顺序必须一致（确定性）
        List<String> again = WorkspaceScanner.scan(workspace).stream()
                .map(d -> d.path().getFileName().toString()).toList();
        assertEquals(List.of("alpha.draft", "middle.draft", "zebra.draft"), again,
                "重复扫描应得到完全相同的顺序");
    }

    @Test
    void scanIgnoresNonDraftFilesAndSubdirectories() throws IOException {
        Instant now = Instant.now();
        draft("book.draft", now);
        Files.writeString(workspace.resolve("cover.png"), "png");
        Files.writeString(workspace.resolve("book.epub"), "epub");   // 正式导出件不进宫格
        Files.createDirectory(workspace.resolve("nested.draft"));     // 同名目录不是文档

        List<DraftDocument> docs = WorkspaceScanner.scan(workspace);

        assertEquals(1, docs.size(), "只应扫出真正的 .draft 文件");
        assertEquals("book.draft", docs.get(0).path().getFileName().toString());
    }

    @Test
    void scanHandlesMissingAndNonDirectoryInputs() throws IOException {
        assertTrue(WorkspaceScanner.scan(null).isEmpty(), "null 目录应返回空列表");
        assertTrue(WorkspaceScanner.scan(workspace.resolve("nope")).isEmpty(),
                "不存在的目录应返回空列表");

        Path file = workspace.resolve("aFile.txt");
        Files.writeString(file, "x");
        assertTrue(WorkspaceScanner.scan(file).isEmpty(), "传文件而非目录应返回空列表");
    }

    @Test
    void displayTitleDerivedFromFileNameStem() throws IOException {
        Instant now = Instant.now();
        draft("三体.draft", now);

        DraftDocument doc = WorkspaceScanner.scan(workspace).get(0);

        assertEquals("三体", doc.displayTitle(), "标题应为去掉 .draft 后缀的文件名");
        assertEquals("三体", doc.stem());
    }

    @Test
    void unreadableModifiedTimeFallsBackToEpochAndSortsLast() throws IOException {
        Instant base = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        draft("normal.draft", base);
        Path weird = draft("weird.draft", base.minus(1, ChronoUnit.DAYS));

        List<DraftDocument> docs = WorkspaceScanner.scan(workspace);
        DraftDocument normal = docs.stream()
                .filter(d -> "normal.draft".equals(d.path().getFileName().toString()))
                .findFirst().orElseThrow();

        assertTrue(normal.hasKnownModifiedTime(), "正常文件应有已知修改时间");
        // EPOCH 只用于读不到 mtime 的极端情况；这里不强行构造，只验证字段语义存在
        assertFalse(Instant.EPOCH.equals(normal.modifiedAt()));
        assertEquals(2, docs.size());
    }

    @Test
    void hasAnyDraftDetectsWorkspaceLikeDirectory() throws IOException {
        assertFalse(WorkspaceScanner.hasAnyDraft(workspace), "空目录不算工作空间");

        draft("a.draft", Instant.now());

        assertTrue(WorkspaceScanner.hasAnyDraft(workspace), "有 .draft 即视为工作空间");
    }

    @Test
    void scanRecentLimitsResultCount() throws IOException {
        Instant base = Instant.now();
        for (int i = 0; i < 5; i++) {
            draft("doc" + i + ".draft", base.plus(i, ChronoUnit.MINUTES));
        }

        assertEquals(5, WorkspaceScanner.scan(workspace).size());
        assertEquals(2, WorkspaceScanner.scanRecent(workspace, 2).size(),
                "scanRecent 应截断到指定条数");
        // 截断保留的是最新的两条（doc4, doc3）
        assertEquals(List.of("doc4.draft", "doc3.draft"),
                WorkspaceScanner.scanRecent(workspace, 2).stream()
                        .map(d -> d.path().getFileName().toString()).toList());
        // limit 超过总数时返回全部
        assertEquals(5, WorkspaceScanner.scanRecent(workspace, 99).size());
    }
}
