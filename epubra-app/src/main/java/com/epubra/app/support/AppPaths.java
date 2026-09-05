package com.epubra.app.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

/**
 * 用户数据目录的统一入口。
 *
 * <h2>目录布局</h2>
 * <pre>
 *   ~/.
 *     └── Epubra/
 *         ├── autosave/          ← 未保存新书的草稿（{@link Autosave} 落点）
 *         ├── webview/           ← JavaFX WebView 缓存（由 user.home 重定向派生）
 *         └── drafts/            ← （预留）项目级草稿（暂未启用）
 * </pre>
 *
 * <h2>为什么不用 com.epubra.app.EpubraApp 这种长名</h2>
 * <p>JavaFX WebView 的 native 缓存在 Windows 上会以 main class 的 FQCN 派生目录名
 * （例如 {@code ~/.com.epubra.app.EpubraApp/webview}），既丑又长。本类通过在
 * {@link EpubraLauncher} 启动最早阶段 {@link #redirectUserHome()} 把
 * {@code user.home} 改写到 {@code ~/.Epubra/}，让 WebView 缓存一并落进来。
 *
 * <h2>软着陆迁移</h2>
 * <p>早期版本使用过 {@code <user.dir>/epubra-autosave} 或
 * {@code ~/epubra-autosave}。{@link #migrateLegacyIfAny()} 会在首次调用
 * {@link #userDataDir()} 时把旧目录下的文件一次性搬到新位置，然后删除空目录。
 * 找不到旧目录或新位置已有同名文件时静默跳过——不做覆盖，迁移是「尽力而为」。
 */
public final class AppPaths {

    /** 用户数据目录名（首字母大写、带点，类似 .git / .idea 的隐藏目录惯例）。 */
    public static final String APP_DIR_NAME = ".Epubra";

    /** 草稿（未保存新书）落点。 */
    public static final String AUTOSAVE_SUBDIR = "autosave";

    /** JavaFX WebView 本地缓存（由 user.home 重定向派生，本类也提供显式预创建）。 */
    public static final String WEBVIEW_SUBDIR = "webview";

    /** 旧版本使用的全局 autosave 目录名（已弃用，迁移用）。 */
    public static final String LEGACY_AUTOSAVE_NAME = "epubra-autosave";

    private static final System.Logger LOG = System.getLogger(AppPaths.class.getName());

    private AppPaths() {
    }

    /**
     * 用户数据根目录：{@code <user.home>/.Epubra/}。目录不存在则懒创建。
     *
     * <p>本方法只读 {@code user.home} 系统属性——{@link EpubraLauncher} 在调用本类
     * 任何路径方法之前会先 {@link #redirectUserHome()} 把 {@code user.home} 重写为
     * {@code ~/.Epubra/} 的父目录之外的某个稳定值，再由本方法把它拼回
     * {@code ~/.Epubra/}。因此重定向时序必须在 launcher 启动最早阶段完成。
     *
     * @return 路径始终以 {@code .Epubra} 结尾；可能不存在（{@link #ensureDirectory(Path)} 失败时）
     */
    public static Path userDataDir() {
        return resolve(APP_DIR_NAME);
    }

    /** 草稿目录：{@code <user.home>/.Epubra/autosave/}。懒创建。 */
    public static Path autosaveDir() {
        return resolve(APP_DIR_NAME, AUTOSAVE_SUBDIR);
    }

    /**
     * WebView 缓存目录：{@code <user.home>/.Epubra/webview/}。
     *
     * <p>实际由 JavaFX native 在第一次 WebView 加载时根据当前 {@code user.home} 派生，
     * 本方法预先创建目录保证 launcher 重定向后 native 能直接写入，而不会因
     * 上层目录缺失失败。
     */
    public static Path webviewCacheDir() {
        return resolve(APP_DIR_NAME, WEBVIEW_SUBDIR);
    }

    /**
     * 把当前进程 {@code user.home} 改写为 {@code <原 user.home>/.Epubra/}，
     * 让 JavaFX WebView 等读 {@code user.home} 的 native 组件把缓存一并落进来。
     *
     * <p>副作用：调用后 {@code System.getProperty("user.home")} 返回新路径；
     * 调用前应当已经先取过一次原值（避免被自己的写入覆盖）。本方法会同时预创建
     * {@link #userDataDir()}、{@link #autosaveDir()}、{@link #webviewCacheDir()}。
     *
     * <p>幂等：连续多次调用结果一致。
     *
     * @return 重写后的 user.home 路径
     */
    public static Path redirectUserHome() {
        String current = System.getProperty("user.home");
        if (current == null || current.isBlank()) {
            current = ".";
        }
        Path currentPath = Path.of(current);
        // 若 user.home 已经被重定向过（值为 ~/.Epubra 本身），不要在它上面再叠一层
        if (currentPath.getFileName() != null
                && APP_DIR_NAME.equals(currentPath.getFileName().toString())) {
            ensureDirectory(currentPath);
            ensureDirectory(currentPath.resolve(AUTOSAVE_SUBDIR));
            ensureDirectory(currentPath.resolve(WEBVIEW_SUBDIR));
            return currentPath;
        }
        Path target = currentPath.resolve(APP_DIR_NAME);
        ensureDirectory(target);
        ensureDirectory(target.resolve(AUTOSAVE_SUBDIR));
        ensureDirectory(target.resolve(WEBVIEW_SUBDIR));
        System.setProperty("user.home", target.toAbsolutePath().toString());
        return target;
    }

    /**
     * 一次性把旧版本残留的全局 autosave 目录搬进新位置。可放心重复调用。
     *
     * <p>旧路径按出现顺序尝试：
     * <ol>
     *   <li>{@code <user.dir>/epubra-autosave}</li>
     *   <li>{@code <旧 user.home>/epubra-autosave}（即重定向前记录的 user.home）</li>
     * </ol>
     *
     * <p>搬运完成后删除空目录；非空则保留留待用户手动清理。冲突文件跳过不覆盖。
     *
     * <p>应当从 {@link #userDataDir()} 第一次被调用时触发，避免每次 autosave 都跑。
     */
    public static void migrateLegacyIfAny() {
        Path dest = autosaveDir();
        for (Path legacy : legacyCandidates()) {
            if (!Files.isDirectory(legacy) || legacy.equals(dest)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(legacy)) {
                stream.forEach(src -> moveIfAbsent(src, dest.resolve(src.getFileName())));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Legacy autosave scan failed: " + legacy + " (" + e.getMessage() + ")", e);
                continue;
            }
            removeIfEmpty(legacy);
        }
    }

    // ---- internals ----

    private static Path resolve(String first, String... rest) {
        Path base = Path.of(System.getProperty("user.home", "."), first);
        Path result = base;
        for (String segment : rest) {
            result = result.resolve(segment);
        }
        return result;
    }

    private static void ensureDirectory(Path dir) {
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Create directory failed: " + dir + " (" + e.getMessage() + ")", e);
        }
    }

    private static java.util.List<Path> legacyCandidates() {
        java.util.List<Path> list = new java.util.ArrayList<>(2);
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            list.add(Path.of(userDir, LEGACY_AUTOSAVE_NAME));
        }
        // 旧 user.home 通过环境变量 USERPROFILE / HOME 兜底；这两个在 Java 层 read-only
        String envHome = System.getenv("USERPROFILE");
        if (envHome == null || envHome.isBlank()) {
            envHome = System.getenv("HOME");
        }
        if (envHome != null && !envHome.isBlank()) {
            list.add(Path.of(envHome, LEGACY_AUTOSAVE_NAME));
        }
        return list;
    }

    private static void moveIfAbsent(Path src, Path dest) {
        try {
            if (Files.exists(dest)) {
                // 已存在则跳过，避免覆盖用户可能手动放进去的文件
                return;
            }
            Files.move(src, dest);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING,
                    "Legacy autosave move skipped: " + src + " -> " + dest
                            + " (" + e.getMessage() + ")", e);
        }
    }

    private static void removeIfEmpty(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            if (stream.findAny().isPresent()) {
                return; // 还有别的文件，留给用户清理
            }
        } catch (IOException e) {
            return;
        }
        try {
            Files.delete(dir);
        } catch (IOException ignored) {
        }
    }
}