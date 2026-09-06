package com.epubra.app.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

/**
 * 工作空间的持久化存储：最近访问列表 + 「上次打开的工作空间」。
 *
 * <h2>与 {@link RecentProjectsStore} 的关系</h2>
 * <p>本类是 2026-09-06 工作空间重构后的<b>替代者</b>。旧模型把「工作空间」和「项目」
 * 分成两条列表（工作空间装项目子目录，项目是 {@code <ws>/<name>/<name>.epub}）；
 * 新模型下项目概念消失，工作空间直接装 {@code *.draft}，因此只需要一条工作空间列表，
 * 外加一个「启动时直达哪个工作空间」的 {@code lastWorkspace} 键。
 *
 * <p>{@code RecentProjectsStore} 的整体删除安排在 P2（{@code DocumentController} 改完之后），
 * 本类先落地，两者可短暂共存。
 *
 * <h2>存储格式</h2>
 * <p>与 {@link RecentProjectsStore} / {@link ThemeManager} 一致：{@link Preferences} +
 * 静态方法。列表用 {@code \u001F}（ASCII Unit Separator）连接——它绝不出现在合法路径里，
 * 避免分隔符冲突。
 *
 * <h2>去重与上限</h2>
 * <p>{@link #add(Path)} 在写入前去重并把命中项移到队首，上限 8 条
 * （文件菜单的「最近的工作空间」子菜单不宜过长）。
 */
public final class WorkspaceStore {

    /** 最近工作空间的最大条数。 */
    public static final int MAX_RECENT = 8;

    private static final String KEY_RECENT = "recentWorkspaces";
    private static final String KEY_LAST = "lastWorkspace";
    private static final String SEP = "\u001F";

    private WorkspaceStore() {
    }

    // ---- 最近列表 ----

    /** 最近访问的工作空间路径（最近优先）。已不存在的目录仍会列出，由调用方自行过滤。 */
    public static List<Path> recent() {
        List<Path> result = new ArrayList<>();
        String stored;
        try {
            stored = preferences().get(KEY_RECENT, "");
        } catch (RuntimeException e) {
            stored = "";
        }
        for (String raw : readList(stored)) {
            try {
                result.add(Path.of(raw));
            } catch (Exception ignored) {
                // 非法路径字符串（手工改过 Preferences）直接跳过
            }
        }
        return result;
    }

    /** 仍存在于磁盘上的最近工作空间——给菜单与启动时用。 */
    public static List<Path> recentExisting() {
        return recent().stream().filter(Files::isDirectory).toList();
    }

    /**
     * 记录一次工作空间访问：去重 + 移到队首 + 截断到 {@link #MAX_RECENT}，
     * 并同步更新 {@link #last()}。
     */
    public static void add(Path workspace) {
        if (workspace == null) {
            return;
        }
        String value = workspace.toString();
        List<String> list = new ArrayList<>(readList(preferences().get(KEY_RECENT, "")));
        list.remove(value);
        list.add(0, value);
        while (list.size() > MAX_RECENT) {
            list.remove(list.size() - 1);
        }
        Preferences prefs = preferences();
        prefs.put(KEY_RECENT, String.join(SEP, list));
        prefs.put(KEY_LAST, value);
        flush(prefs);
    }

    public static void remove(Path workspace) {
        if (workspace == null) {
            return;
        }
        String value = workspace.toString();
        List<String> list = new ArrayList<>(readList(preferences().get(KEY_RECENT, "")));
        if (list.remove(value)) {
            Preferences prefs = preferences();
            prefs.put(KEY_RECENT, String.join(SEP, list));
            // 删掉的正好是当前工作空间 → 清空 last，下次启动走引导态
            if (value.equals(prefs.get(KEY_LAST, null))) {
                prefs.remove(KEY_LAST);
            }
            flush(prefs);
        }
    }

    // ---- 上次打开的工作空间 ----

    /**
     * 上次打开的工作空间——启动时直达它。
     *
     * <p>该目录<b>已不存在</b>时返回 {@link Optional#empty()}：调用方应据此回退到
     * 「选择工作空间」引导态，而不是把一个失效路径塞给宫格。
     */
    public static Optional<Path> last() {
        String raw;
        try {
            raw = preferences().get(KEY_LAST, null);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            Path p = Path.of(raw);
            return Files.isDirectory(p) ? Optional.of(p) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 只更新「上次打开的工作空间」，不动最近列表（用于启动恢复等场景）。 */
    public static void setLast(Path workspace) {
        if (workspace == null) {
            return;
        }
        Preferences prefs = preferences();
        prefs.put(KEY_LAST, workspace.toString());
        flush(prefs);
    }

    /** 清除「上次打开的工作空间」——用于「关闭工作空间」回到引导态。 */
    public static void clearLast() {
        Preferences prefs = preferences();
        prefs.remove(KEY_LAST);
        flush(prefs);
    }

    /** 清理已不存在的工作空间条目。静默执行，IO 失败就保留原值。 */
    public static void pruneMissing() {
        List<Path> kept = recent().stream().filter(Files::isDirectory).toList();
        List<String> values = kept.stream().map(Path::toString).toList();
        if (values.size() != recent().size()) {
            Preferences prefs = preferences();
            prefs.put(KEY_RECENT, String.join(SEP, values));
            flush(prefs);
        }
    }

    // ---- 内部 ----

    private static List<String> readList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> result = new ArrayList<>();
        for (String part : raw.split(SEP)) {
            if (!part.isEmpty()) {
                result.add(part);
            }
        }
        return result;
    }

    private static void flush(Preferences prefs) {
        try {
            prefs.flush();
        } catch (BackingStoreException | RuntimeException ignored) {
            // 持久化不可用就放弃，下次启动列表不会更新
        }
    }

    private static Preferences preferences() {
        return PreferenceNodes.node("/Epubra/WorkspaceStore");
    }

    /** 测试用：清空本类写入的全部键。 */
    public static void resetForTesting() {
        Preferences prefs = preferences();
        prefs.remove(KEY_RECENT);
        prefs.remove(KEY_LAST);
        flush(prefs);
    }
}
