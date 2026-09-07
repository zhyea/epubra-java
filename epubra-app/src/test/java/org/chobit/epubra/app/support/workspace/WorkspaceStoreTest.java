package org.chobit.epubra.app.support.workspace;

import org.chobit.epubra.app.support.platform.PreferenceNodes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceStore} 契约测试——最近工作空间列表 + 「上次打开的工作空间」。
 *
 * <p>底层是 {@link java.util.prefs.Preferences}，跨测试共享同一份存储，
 * 故每个用例前后都要 {@link WorkspaceStore#resetForTesting()} 隔离。
 */
class WorkspaceStoreTest {

    @TempDir
    Path dirA;
    @TempDir
    Path dirB;
    @TempDir
    Path dirC;

    @BeforeEach
    void setUp() {
        PreferenceNodes.useInMemoryForTesting();
        WorkspaceStore.resetForTesting();
    }

    @AfterEach
    void tearDown() {
        WorkspaceStore.resetForTesting();
        PreferenceNodes.resetForTesting();
    }

    @Test
    void addPutsNewestFirstAndDeduplicates() {
        WorkspaceStore.add(dirA);
        WorkspaceStore.add(dirB);
        WorkspaceStore.add(dirA); // 再次访问 A → 应移到队首

        List<Path> recent = WorkspaceStore.recent();

        assertEquals(List.of(dirA, dirB), recent, "最近的排最前，且去重");
    }

    @Test
    void addAlsoUpdatesLast() {
        WorkspaceStore.add(dirA);
        assertEquals(dirA, WorkspaceStore.last().orElseThrow(), "add 应同步记录 last");

        WorkspaceStore.add(dirB);
        assertEquals(dirB, WorkspaceStore.last().orElseThrow(), "后访问的应成为 last");
    }

    @Test
    void lastReturnsEmptyWhenUnsetOrDirectoryDeleted() throws IOException {
        assertTrue(WorkspaceStore.last().isEmpty(), "未设置过时应为空");

        Path doomed = Files.createDirectory(dirA.resolve("doomed"));
        WorkspaceStore.add(doomed);
        assertEquals(doomed, WorkspaceStore.last().orElseThrow());

        Files.delete(doomed); // 目录被删
        assertTrue(WorkspaceStore.last().isEmpty(),
                "last 指向的目录已不存在时应返回 empty，让启动流程回退到引导态");
    }

    @Test
    void recentExistingFiltersDeletedDirectories() throws IOException {
        Path doomed = Files.createDirectory(dirA.resolve("gone"));
        WorkspaceStore.add(dirA);
        WorkspaceStore.add(doomed);

        assertEquals(2, WorkspaceStore.recent().size());
        Files.delete(doomed);

        assertEquals(1, WorkspaceStore.recentExisting().size(), "recentExisting 应过滤掉已删除的");
        assertEquals(dirA, WorkspaceStore.recentExisting().get(0));
    }

    @Test
    void removeDropsEntryAndClearsLastIfItWasCurrent() {
        WorkspaceStore.add(dirA);
        WorkspaceStore.add(dirB);
        // 后访问的 dirB 成为 last
        assertEquals(dirB, WorkspaceStore.last().orElseThrow());

        WorkspaceStore.remove(dirB);

        assertFalse(WorkspaceStore.recent().contains(dirB), "应从列表移除");
        assertTrue(WorkspaceStore.last().isEmpty(),
                "移除的正好是当前工作空间时，last 应一并清空");
        // 移除非当前项不应影响 last
        WorkspaceStore.add(dirC);
        WorkspaceStore.remove(dirA);
        assertEquals(dirC, WorkspaceStore.last().orElseThrow(),
                "移除的不是当前工作空间时，last 应保持不变");
    }

    @Test
    void maxRecentTrimsOldest() {
        for (int i = 0; i < WorkspaceStore.MAX_RECENT + 4; i++) {
            Path p = dirA.resolve("ws" + i);
            try {
                Files.createDirectory(p);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            WorkspaceStore.add(p);
        }

        assertEquals(WorkspaceStore.MAX_RECENT, WorkspaceStore.recent().size(),
                "列表应截断到 MAX_RECENT 条");
    }

    @Test
    void setLastAndClearLastDoNotTouchRecentList() {
        WorkspaceStore.add(dirA);
        WorkspaceStore.setLast(dirB);

        assertEquals(dirB, WorkspaceStore.last().orElseThrow(), "setLast 应生效");
        assertEquals(1, WorkspaceStore.recent().size(), "setLast 不应改动最近列表");

        WorkspaceStore.clearLast();
        assertTrue(WorkspaceStore.last().isEmpty(), "clearLast 后应为空");
        assertEquals(1, WorkspaceStore.recent().size(), "clearLast 不应改动最近列表");
    }

    @Test
    void pruneMissingRemovesDeletedEntries() throws IOException {
        Path doomed = Files.createDirectory(dirB.resolve("temp"));
        WorkspaceStore.add(dirA);
        WorkspaceStore.add(doomed);
        assertEquals(2, WorkspaceStore.recent().size());

        Files.delete(doomed);
        WorkspaceStore.pruneMissing();

        assertEquals(List.of(dirA), WorkspaceStore.recent(), "pruneMissing 应清掉已删除的条目");
    }
}
