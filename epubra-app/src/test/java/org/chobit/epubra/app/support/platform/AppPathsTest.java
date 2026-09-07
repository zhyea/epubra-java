package org.chobit.epubra.app.support.platform;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppPaths} 工具类契约测试。
 *
 * <p>重点验证：
 * <ul>
 *   <li>{@link AppPaths#userDataDir()} / {@link AppPaths#autosaveDir()} /
 *       {@link AppPaths#webviewCacheDir()} 基于 {@code user.home} 派生</li>
 *   <li>{@link AppPaths#redirectUserHome()} 修改 {@code user.home} 系统属性并创建子目录</li>
 *   <li>{@link AppPaths#redirectUserHome()} 幂等</li>
 *   <li>{@link AppPaths#migrateLegacyIfAny()} 把旧 {@code epubra-autosave} 搬进新位置</li>
 * </ul>
 */
class AppPathsTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;
    private String originalUserDir;
    private String originalUserProfile;

    @BeforeEach
    void saveProperties() {
        originalUserHome = System.getProperty("user.home");
        originalUserDir = System.getProperty("user.dir");
        originalUserProfile = System.getenv("USERPROFILE");
    }

    @AfterEach
    void restoreProperties() {
        if (originalUserHome == null) {
            System.clearProperty("user.home");
        } else {
            System.setProperty("user.home", originalUserHome);
        }
        if (originalUserDir == null) {
            System.clearProperty("user.dir");
        } else {
            System.setProperty("user.dir", originalUserDir);
        }
        // 清掉 AppPaths 注入的派生属性 + static 标记,避免跨测试污染
        System.clearProperty("epubra.userDataDir");
        AppPaths.resetForTesting();
    }

    private void pointAt(Path fakeHome) throws IOException {
        Files.createDirectories(fakeHome);
        System.setProperty("user.home", fakeHome.toAbsolutePath().toString());
        System.setProperty("user.dir", fakeHome.toAbsolutePath().toString());
        // USERPROFILE 在 JDK 是 read-only env,无法 set；migrateLegacyIfAny 会忽略它
    }

    // ---- 常量 ----

    @Test
    void appDirNameConstant() {
        assertEquals(".Epubra", AppPaths.APP_DIR_NAME);
        assertEquals("autosave", AppPaths.AUTOSAVE_SUBDIR);
        assertEquals("webview", AppPaths.WEBVIEW_SUBDIR);
        assertEquals("epubra-autosave", AppPaths.LEGACY_AUTOSAVE_NAME);
    }

    // ---- 路径派生 ----

    @Test
    void userDataDirIsHomePlusDotEpubra(@TempDir Path home) {
        System.setProperty("user.home", home.toAbsolutePath().toString());
        Path expected = home.resolve(".Epubra");
        assertEquals(expected, AppPaths.userDataDir());
    }

    @Test
    void autosaveDirIsUnderUserDataDir(@TempDir Path home) {
        System.setProperty("user.home", home.toAbsolutePath().toString());
        assertEquals(home.resolve(".Epubra/autosave"), AppPaths.autosaveDir());
    }

    @Test
    void webviewCacheDirIsUnderUserDataDir(@TempDir Path home) {
        System.setProperty("user.home", home.toAbsolutePath().toString());
        assertEquals(home.resolve(".Epubra/webview"), AppPaths.webviewCacheDir());
    }

    @Test
    void fallbackToDotWhenUserHomeBlank(@TempDir Path home) {
        System.setProperty("user.home", "");
        // 不会抛异常,且返回路径以 .Epubra 结尾
        Path result = AppPaths.userDataDir();
        assertNotNull(result);
        assertEquals(".Epubra", result.getFileName().toString());
    }

    // ---- redirectUserHome ----

    @Test
    void redirectUserHomeSetsSystemPropertyAndCreatesDirs(@TempDir Path home) throws IOException {
        pointAt(home);

        Path redirected = AppPaths.redirectUserHome();

        // 1) 返回值 = <home>/.Epubra
        assertEquals(home.resolve(".Epubra"), redirected);
        // 2) 系统属性已更新
        assertEquals(redirected.toAbsolutePath().toString(), System.getProperty("user.home"));
        // 3) 三个子目录都已创建
        assertTrue(Files.isDirectory(redirected));
        assertTrue(Files.isDirectory(redirected.resolve("autosave")));
        assertTrue(Files.isDirectory(redirected.resolve("webview")));
    }

    @Test
    void redirectUserHomeIsIdempotent(@TempDir Path home) throws IOException {
        pointAt(home);

        Path first = AppPaths.redirectUserHome();
        Path second = AppPaths.redirectUserHome();
        Path third = AppPaths.redirectUserHome();

        assertEquals(first, second);
        assertEquals(second, third);
        // 第二次起不会再叠一层 .Epubra/.Epubra
        assertEquals(home.resolve(".Epubra"), first);
        assertFalse(Files.exists(home.resolve(".Epubra/.Epubra")));
    }

    @Test
    void redirectUserHomeFromAlreadyRedirectedHomeStaysStable(@TempDir Path home) throws IOException {
        pointAt(home);
        AppPaths.redirectUserHome(); // 第一次:user.home = ~/.Epubra
        String afterFirst = System.getProperty("user.home");

        AppPaths.redirectUserHome(); // 第二次:user.home 已经是 ~/.Epubra,不要叠
        String afterSecond = System.getProperty("user.home");

        assertEquals(afterFirst, afterSecond);
    }

    // ---- migrateLegacyIfAny ----

    @Test
    void migrateMovesLegacyFilesIntoNewDir(@TempDir Path home) throws IOException {
        pointAt(home);
        AppPaths.redirectUserHome();

        // 在 <home>/epubra-autosave 放一份旧草稿（绕过 redirect,模拟迁移前状态）
        Path legacy = home.resolve("epubra-autosave");
        Files.createDirectories(legacy);
        Path oldDraft = legacy.resolve("untitled.draft");
        Files.writeString(oldDraft, "legacy content");

        AppPaths.migrateLegacyIfAny();

        Path newDir = AppPaths.autosaveDir();
        Path moved = newDir.resolve("untitled.draft");
        assertTrue(Files.exists(moved), "旧草稿应被搬到新 autosave 目录");
        assertEquals("legacy content", Files.readString(moved));
        // 旧目录应已被删除（搬运后变空）
        assertFalse(Files.exists(legacy), "空旧目录应被清理");
    }

    @Test
    void migrateSkipsOnConflictWithoutOverwrite(@TempDir Path home) throws IOException {
        pointAt(home);
        AppPaths.redirectUserHome();

        Path legacy = home.resolve("epubra-autosave");
        Files.createDirectories(legacy);
        Path oldDraft = legacy.resolve("untitled.draft");
        Files.writeString(oldDraft, "OLD");
        // 新位置已有同名文件且内容不同
        Path newDraft = AppPaths.autosaveDir().resolve("untitled.draft");
        Files.writeString(newDraft, "NEW");

        AppPaths.migrateLegacyIfAny();

        // 旧文件未覆盖
        assertEquals("NEW", Files.readString(newDraft));
        // 旧文件还在原位置(没搬走)
        assertTrue(Files.exists(oldDraft));
    }

    @Test
    void migrateIsSilentWhenNoLegacyDir(@TempDir Path home) throws IOException {
        pointAt(home);
        AppPaths.redirectUserHome();

        // 没有 epubra-autosave 目录,不应抛异常
        AppPaths.migrateLegacyIfAny();
        AppPaths.migrateLegacyIfAny(); // 重复调用也不应抛

        assertTrue(Files.isDirectory(AppPaths.autosaveDir()));
    }

    @Test
    void migrateKeepsLegacyDirWhenOtherFilesRemain(@TempDir Path home) throws IOException {
        pointAt(home);
        AppPaths.redirectUserHome();

        Path legacy = home.resolve("epubra-autosave");
        Files.createDirectories(legacy);
        // 一个 .draft + 一个非 .draft 残留
        Files.writeString(legacy.resolve("untitled.draft"), "x");
        Files.writeString(legacy.resolve("random.txt"), "y");

        AppPaths.migrateLegacyIfAny();

        // 旧目录还有非 .draft 文件,保留不删
        assertTrue(Files.isDirectory(legacy));
        assertTrue(Files.exists(legacy.resolve("random.txt")));
    }

    // ---- 联动场景 ----

    @Test
    void userDataDirAfterRedirectIsUnderNewHome(@TempDir Path home) throws IOException {
        pointAt(home);
        AppPaths.redirectUserHome();
        // redirect 后 userDataDir 直接返回重定向目标,不再二次拼接
        assertEquals(Path.of(System.getProperty("user.home")), AppPaths.userDataDir());
        assertEquals(Path.of(System.getProperty("user.home"), "autosave"),
                AppPaths.autosaveDir());
        assertEquals(Path.of(System.getProperty("user.home"), "webview"),
                AppPaths.webviewCacheDir());
    }

    @Test
    void differentHomesYieldDifferentPaths(@TempDir Path home1, @TempDir Path home2) {
        System.setProperty("user.home", home1.toAbsolutePath().toString());
        Path a1 = AppPaths.userDataDir();
        System.setProperty("user.home", home2.toAbsolutePath().toString());
        Path a2 = AppPaths.userDataDir();
        assertNotEquals(a1, a2);
    }
}