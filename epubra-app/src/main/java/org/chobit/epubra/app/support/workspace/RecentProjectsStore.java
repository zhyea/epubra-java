package org.chobit.epubra.app.support.workspace;

import org.chobit.epubra.app.support.document.AutosaveConfig;
import org.chobit.epubra.app.ui.support.editor.ThemeManager;
import org.chobit.epubra.app.support.platform.PreferenceNodes;
import org.chobit.epubra.app.support.platform.PreferencesMigrator;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 最近工作空间 + 最近项目的持久化存储。
 *
 * <p>模型沿用 JetBrains IDEA 的思路：
 * <ul>
 *   <li><b>工作空间</b>：一个父目录，用户在新建项目时挑的容器；下可放多个项目目录</li>
 *   <li><b>项目</b>：一个 {@code <workspace>/<name>/<name>.epub} + 同级 {@code .epubra/} 元数据目录</li>
 * </ul>
 *
 * <p>持久化方案与 {@link ThemeManager}/{@link AutosaveConfig} 一致：{@link Preferences} +
 * 静态方法，便于单元测试与跨进程恢复。
 *
 * <h2>存储格式</h2>
 * <p>每个列表按「最近优先」顺序用 {@code \u001F}（ASCII Unit Separator）连接成单字符串。
 * 选 Unit Separator 是因为它绝不出现在合法文件路径里——避免分隔符冲突。
 *
 * <h2>去重与上限</h2>
 * <p>{@link #addWorkspace} / {@link #addProject} 在写入前去重，并把命中项移到队首。
 * 上限 10 条（与 IDEA 默认 50 不同，桌面阅读器场景不需要那么多）。
 */
public final class RecentProjectsStore {

    /** 最近工作空间的最大条数。 */
    public static final int MAX_WORKSPACES = 10;
    /** 最近项目的最大条数。 */
    public static final int MAX_PROJECTS = 10;

    private static final String KEY_WORKSPACES = "recentWorkspaces";
    private static final String KEY_PROJECTS = "recentProjects";
    private static final String SEP = "\u001F";

    private RecentProjectsStore() {
    }

    // ---- 工作空间 ----

    public static List<String> workspaces() {
        try {
            return readList(preferences().get(KEY_WORKSPACES, ""));
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    public static void addWorkspace(String workspace) {
        if (workspace == null || workspace.isBlank()) {
            return;
        }
        List<String> list = new ArrayList<>(workspaces());
        list.remove(workspace);
        list.add(0, workspace);
        trimTo(list, MAX_WORKSPACES);
        writeList(KEY_WORKSPACES, list);
    }

    public static void removeWorkspace(String workspace) {
        if (workspace == null) {
            return;
        }
        List<String> list = new ArrayList<>(workspaces());
        if (list.remove(workspace)) {
            writeList(KEY_WORKSPACES, list);
        }
    }

    // ---- 项目 ----

    public static List<String> projects() {
        try {
            return readList(preferences().get(KEY_PROJECTS, ""));
        } catch (RuntimeException e) {
            return new ArrayList<>();
        }
    }

    public static void addProject(String projectFile) {
        if (projectFile == null || projectFile.isBlank()) {
            return;
        }
        List<String> list = new ArrayList<>(projects());
        list.remove(projectFile);
        list.add(0, projectFile);
        trimTo(list, MAX_PROJECTS);
        writeList(KEY_PROJECTS, list);
    }

    public static void removeProject(String projectFile) {
        if (projectFile == null) {
            return;
        }
        List<String> list = new ArrayList<>(projects());
        if (list.remove(projectFile)) {
            writeList(KEY_PROJECTS, list);
        }
    }

    /**
     * 过滤掉已不存在的项目目录（含已被删除的 workspace 与 .epub 文件）。
     * 静默执行，磁盘 IO 失败就保留原值。
     */
    public static void pruneMissing() {
        boolean changed = false;
        List<String> keptProjects = new ArrayList<>();
        for (String p : projects()) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(p))) {
                keptProjects.add(p);
            } else {
                changed = true;
            }
        }
        if (changed) {
            writeList(KEY_PROJECTS, keptProjects);
        }

        boolean changedWs = false;
        List<String> keptWs = new ArrayList<>();
        for (String w : workspaces()) {
            if (java.nio.file.Files.exists(java.nio.file.Path.of(w))) {
                keptWs.add(w);
            } else {
                changedWs = true;
            }
        }
        if (changedWs) {
            writeList(KEY_WORKSPACES, keptWs);
        }
    }

    // ---- 内部 ----

    private static List<String> readList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        String[] parts = raw.split(SEP);
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }

    private static void writeList(String key, List<String> list) {
        try {
            Preferences prefs = preferences();
            prefs.put(key, String.join(SEP, list));
            prefs.flush();
        } catch (BackingStoreException | RuntimeException ignored) {
            // 持久化不可用就放弃，下次启动清单不会更新
        }
    }

    private static void trimTo(List<String> list, int max) {
        while (list.size() > max) {
            list.remove(list.size() - 1);
        }
    }

    private static Preferences preferences() {
        Preferences prefs = PreferenceNodes.node("/Epubra/RecentProjectsStore");
        PreferencesMigrator.migrate(
                PreferenceNodes.node("/com/epubra/app/support/RecentProjectsStore"),
                prefs);
        return prefs;
    }
}
