package com.epubra.app.support;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecentProjectsStore} 契约测试。
 *
 * <p>Preferences 通过 {@link PreferenceNodes} 切到内存根节点，避免污染用户配置，
 * 也避免 Windows 注册表权限影响测试结果。
 */
class RecentProjectsStoreTest {

    @BeforeEach
    void setUp() {
        PreferenceNodes.useInMemoryForTesting();
    }

    @AfterEach
    void tearDown() {
        clear();
        PreferenceNodes.resetForTesting();
    }

    private void clear() {
        for (String w : RecentProjectsStore.workspaces()) {
            RecentProjectsStore.removeWorkspace(w);
        }
        for (String p : RecentProjectsStore.projects()) {
            RecentProjectsStore.removeProject(p);
        }
    }

    @Test
    void workspaces_emptyByDefault() {
        assertTrue(RecentProjectsStore.workspaces().isEmpty());
    }

    @Test
    void projects_emptyByDefault() {
        assertTrue(RecentProjectsStore.projects().isEmpty());
    }

    @Test
    void addWorkspace_mostRecentFirst() {
        RecentProjectsStore.addWorkspace("D:/Books");
        RecentProjectsStore.addWorkspace("D:/Articles");
        RecentProjectsStore.addWorkspace("D:/Notes");

        assertEquals(java.util.List.of("D:/Notes", "D:/Articles", "D:/Books"),
                RecentProjectsStore.workspaces());
    }

    @Test
    void addWorkspace_dedupesAndMovesToFront() {
        RecentProjectsStore.addWorkspace("A");
        RecentProjectsStore.addWorkspace("B");
        RecentProjectsStore.addWorkspace("A");  // 重复

        assertEquals(java.util.List.of("A", "B"), RecentProjectsStore.workspaces());
    }

    @Test
    void addWorkspace_ignoresNullAndBlank() {
        RecentProjectsStore.addWorkspace(null);
        RecentProjectsStore.addWorkspace("");
        RecentProjectsStore.addWorkspace("   ");
        assertTrue(RecentProjectsStore.workspaces().isEmpty());
    }

    @Test
    void addProject_mostRecentFirst() {
        RecentProjectsStore.addProject("/x/a.epub");
        RecentProjectsStore.addProject("/x/b.epub");
        assertEquals(java.util.List.of("/x/b.epub", "/x/a.epub"),
                RecentProjectsStore.projects());
    }

    @Test
    void addProject_capsAtMax() {
        for (int i = 0; i < RecentProjectsStore.MAX_PROJECTS + 5; i++) {
            RecentProjectsStore.addProject("/x/" + i + ".epub");
        }
        assertEquals(RecentProjectsStore.MAX_PROJECTS, RecentProjectsStore.projects().size());
        // 最新优先：最后加入的应在队首
        assertEquals("/x/" + (RecentProjectsStore.MAX_PROJECTS + 4) + ".epub",
                RecentProjectsStore.projects().get(0));
    }

    @Test
    void addWorkspace_capsAtMax() {
        for (int i = 0; i < RecentProjectsStore.MAX_WORKSPACES + 5; i++) {
            RecentProjectsStore.addWorkspace("D:/ws-" + i);
        }
        assertEquals(RecentProjectsStore.MAX_WORKSPACES, RecentProjectsStore.workspaces().size());
    }

    @Test
    void remove_works() {
        RecentProjectsStore.addWorkspace("D:/A");
        RecentProjectsStore.addWorkspace("D:/B");
        RecentProjectsStore.removeWorkspace("D:/A");

        assertEquals(java.util.List.of("D:/B"), RecentProjectsStore.workspaces());
    }

    @Test
    void remove_silentOnUnknown() {
        RecentProjectsStore.removeWorkspace("D:/never-existed");
        assertTrue(RecentProjectsStore.workspaces().isEmpty());
    }

    @Test
    void pruneMissing_removesDeadEntries(@org.junit.jupiter.api.io.TempDir Path tempDir)
            throws Exception {
        // 真实存在的 workspace
        Path liveWs = Files.createDirectory(tempDir.resolve("live-ws"));
        // 不存在的 workspace（用一个明显不可能存在的路径）
        String deadWs = tempDir.resolve("dead-ws-" + System.nanoTime()).toString();
        RecentProjectsStore.addWorkspace(deadWs);
        RecentProjectsStore.addWorkspace(liveWs.toString());

        Path liveProject = Files.createDirectory(tempDir.resolve("live-proj"));
        String deadProject = tempDir.resolve("dead-proj-" + System.nanoTime() + ".epub").toString();
        RecentProjectsStore.addProject(deadProject);
        RecentProjectsStore.addProject(liveProject.toString());

        RecentProjectsStore.pruneMissing();

        assertFalse(RecentProjectsStore.workspaces().contains(deadWs));
        assertTrue(RecentProjectsStore.workspaces().contains(liveWs.toString()));
        assertFalse(RecentProjectsStore.projects().contains(deadProject));
        assertTrue(RecentProjectsStore.projects().contains(liveProject.toString()));
    }

    @Test
    void survivesRoundTripThroughPreferences() {
        RecentProjectsStore.addWorkspace("D:/Round-trip");
        // 直接重新打开 Preferences 节点应能读到
        java.util.prefs.Preferences p = PreferenceNodes.node("/Epubra/RecentProjectsStore");
        String raw = p.get("recentWorkspaces", "");
        assertTrue(raw.contains("D:/Round-trip"));
    }
}
