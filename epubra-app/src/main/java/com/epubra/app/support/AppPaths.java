package com.epubra.app.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
 *
 * <h2>幂等与重入</h2>
 * <p>{@link #redirectUserHome()} 用 {@link AtomicBoolean} 标记已完成的重定向，
 * 多次调用结果一致；{@link #userDataDir()} 等路径方法优先读
 * {@code epubra.userDataDir} 系统属性，避免在已被重定向的 {@code user.home} 上
 * 二次拼接产生 {@code ~/.Epubra/.Epubra/autosave} 这种嵌套。
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

    /** 重定向状态标记：{@link #redirectUserHome()} 完成后置 true，避免重复改写 user.home。 */
    private static final AtomicBoolean REDIRECTED = new AtomicBoolean(false);

    /** 重定向后的目标路径属性名，供 {@link #userDataDir()} 等方法优先读取。 */
    private static final String REDIRECTED_PATH_PROPERTY = "epubra.userDataDir";

    private static final System.Logger LOG = System.getLogger(AppPaths.class.getName());

    private AppPaths() {
    }

    /**
     * 用户数据根目录：{@code <user.home>/.Epubra/}。
     *
     * <p>优先级：
     * <ol>
     *   <li>{@link #redirectUserHome()} 已执行 → 直接返回其写入的 {@code epubra.userDataDir}</li>
     *   <li>否则基于当前 {@code user.home} 派生</li>
     * </ol>
     */
    public static Path userDataDir() {
        String explicit = System.getProperty(REDIRECTED_PATH_PROPERTY);
        if (explicit != null && !explicit.isBlank()) {
            return Path.of(explicit);
        }
        String home = System.getProperty("user.home", ".");
        if (home.isBlank()) {
            home = ".";
        }
        return Path.of(home, APP_DIR_NAME);
    }

    /** 草稿目录：{@code <user.home>/.Epubra/autosave/}。 */
    public static Path autosaveDir() {
        return userDataDir().resolve(AUTOSAVE_SUBDIR);
    }

    /**
     * WebView 缓存目录：{@code <user.home>/.Epubra/webview/}。
     *
     * <p>实际由 JavaFX native 在第一次 WebView 加载时根据当前 {@code user.home} 派生，
     * 本方法返回的路径供 launcher 用于预创建。
     */
    public static Path webviewCacheDir() {
        return userDataDir().resolve(WEBVIEW_SUBDIR);
    }

    /**
     * 测试 hook：清掉 {@link #REDIRECTED} 标记与派生属性，使后续断言可以重新走
     * "user.home 未被重定向"分支。仅供单元测试调用，生产代码不应触碰。
     */
    static void resetForTesting() {
        synchronized (AppPaths.class) {
            REDIRECTED.set(false);
            System.clearProperty(REDIRECTED_PATH_PROPERTY);
        }
    }

    /**
     * 把当前进程 {@code user.home} 改写为 {@code <原 user.home>/.Epubra/}，
     * 让 JavaFX WebView 等读 {@code user.home} 的 native 组件把缓存一并落进来。
     *
     * <p>幂等：连续多次调用结果一致；并发安全靠 {@link AtomicBoolean}。
     *
     * @return 重写后的 user.home 路径
     */
    public static Path redirectUserHome() {
        if (REDIRECTED.get()) {
            return userDataDir();
        }
        synchronized (AppPaths.class) {
            if (REDIRECTED.get()) {
                return userDataDir();
            }
            String current = System.getProperty("user.home");
            if (current == null || current.isBlank()) {
                current = ".";
            }
            Path target = Path.of(current, APP_DIR_NAME);
            ensureDirectory(target);
            ensureDirectory(target.resolve(AUTOSAVE_SUBDIR));
            ensureDirectory(target.resolve(WEBVIEW_SUBDIR));
            String absolute = target.toAbsolutePath().toString();
            // 把目标路径写到独立属性,userDataDir() 优先用它,避免二次拼接
            System.setProperty(REDIRECTED_PATH_PROPERTY, absolute);
            // 同时改写 user.home,让 JavaFX WebView native 缓存跟随
            System.setProperty("user.home", absolute);
            REDIRECTED.set(true);
            return target;
        }
    }

    /**
     * 一次性把旧版本残留的全局 autosave 目录搬进新位置。可放心重复调用。
     *
     * <p>旧路径按出现顺序尝试：
     * <ol>
     *   <li>{@code <user.dir>/epubra-autosave}</li>
     *   <li>{@code <env USERPROFILE 或 HOME>/epubra-autosave}</li>
     * </ol>
     *
     * <p>搬运完成后删除空目录；非空则保留留待用户手动清理。冲突文件跳过不覆盖。
     */
    public static void migrateLegacyIfAny() {
        Path dest = autosaveDir();
        for (Path legacy : legacyCandidates()) {
            if (!Files.isDirectory(legacy) || legacy.equals(dest)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(legacy)) {
                // 只搬 .draft 文件:旧目录里若还有别的东西(用户手动放的)保留不动
                stream
                        .filter(p -> p.getFileName().toString().endsWith(Autosave.DRAFT_SUFFIX))
                        .forEach(src -> moveIfAbsent(src, dest.resolve(src.getFileName())));
            } catch (IOException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "Legacy autosave scan failed: " + legacy + " (" + e.getMessage() + ")", e);
                continue;
            }
            removeIfEmpty(legacy);
        }
    }

    // ---- internals ----

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

    private static List<Path> legacyCandidates() {
        List<Path> list = new ArrayList<>(2);
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isBlank()) {
            list.add(Path.of(userDir, LEGACY_AUTOSAVE_NAME));
        }
        // 旧 user.home 通过环境变量 USERPROFILE / HOME 兜底
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
                return;
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